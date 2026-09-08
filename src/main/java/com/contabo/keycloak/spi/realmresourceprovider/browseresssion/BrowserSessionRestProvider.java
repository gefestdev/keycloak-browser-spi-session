package com.contabo.keycloak.spi.realmresourceprovider.browseresssion;

import org.jboss.resteasy.reactive.NoCache;
import org.keycloak.TokenVerifier;
import org.keycloak.authorization.util.Tokens;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.VerificationException;
import org.keycloak.events.Errors;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.utils.MediaType;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

public class BrowserSessionRestProvider implements RealmResourceProvider {

  private static final String TEXT_HTML_UTF_8 = "text/html; charset=utf-8";
  private static final long MAX_INTERSTITIAL_DELAY_MS = 10_000L;

  private final KeycloakSession keycloakSession;

  public BrowserSessionRestProvider(KeycloakSession session) {
    this.keycloakSession = session;
  }

  public void close() {
    // NOP
  }

  public Object getResource() {
    return this;
  }

  @OPTIONS
  @Path("init")
  @NoCache
  @Produces({ MediaType.TEXT_PLAIN_UTF_8 })
  public Response checkCORS(@QueryParam("publicClient") String targetClient) {
    String BSAPI_ACCESS_CONTROL_ALLOW_HEADERS = System.getenv("BSAPI_ACCESS_CONTROL_ALLOW_HEADERS");
    if (null == BSAPI_ACCESS_CONTROL_ALLOW_HEADERS) {
      BSAPI_ACCESS_CONTROL_ALLOW_HEADERS = "origin, content-type, accept, authorization";
    }
    String accessControlAllowOrigin = this.getAccessControlAllowOrigin(targetClient);
    return Response
        .status(200)
        .header("Access-Control-Allow-Origin", accessControlAllowOrigin)
        .header("Access-Control-Allow-Credentials", "true")
        .header("Access-Control-Allow-Headers",
            BSAPI_ACCESS_CONTROL_ALLOW_HEADERS)
        .header("Access-Control-Allow-Methods",
            "GET, OPTIONS")
        .entity("")
        .build();
  }

  /**
   * Creates a browser session out of the access token passed as `token` query
   * parameter and redirects back to the application afterwards.
   *
   * The endpoint is meant to be entered by a top level browser navigation
   * (application -> here -> back to the application), which is why the token
   * travels as a query parameter: a redirect cannot carry an `Authorization`
   * header.
   */
  @GET
  @Path("init")
  @NoCache
  public Response setSessionCookies(@QueryParam("publicClient") String targetClient,
      @QueryParam("token") String token,
      @QueryParam("redirect_uri") String redirectUri) {
    final ClientModel newClient = this.getValidatedTargetClient(targetClient);
    final AccessToken validToken = this.validateAccessToken(token);
    // resolve the redirect target before touching any state, so a bad request
    // does not leave a dangling user session behind
    final URI targetUri = this.resolveRedirectUri(redirectUri, newClient);

    final RealmModel realm = this.keycloakSession.getContext().getRealm();

    // create new user session and bind it to the target client Id
    final UserModel user = this.keycloakSession.users().getUserById(realm, validToken.getSubject());
    if (null == user || !user.isEnabled()) {
      throw new ErrorResponseException(Errors.USER_NOT_FOUND, "User not found or disabled",
          Response.Status.UNAUTHORIZED);
    }
    final ClientConnection clientConnection = this.keycloakSession.getContext().getConnection();
    // note: the short `createUserSession` overload is still deprecated in Keycloak 26,
    // the explicit one below is its exact equivalent (no pre-set id, persistent session)
    UserSessionModel newUserSession = this.keycloakSession.sessions().createUserSession(
        null, realm, user, user.getUsername(),
        clientConnection.getRemoteAddr(), "KEYCLOAK", false, null, null,
        UserSessionModel.SessionPersistenceState.PERSISTENT);
    this.keycloakSession.sessions().createClientSession(realm,
        newClient, newUserSession);

    // create cookies - these are attached to the redirect response below
    final UriInfo uriInfo = this.keycloakSession.getContext().getUri();
    AuthenticationManager.createLoginCookie(this.keycloakSession, realm,
        newUserSession.getUser(), newUserSession,
        uriInfo, clientConnection);

    // finally send the browser back to the application it came from - through a
    // page the browser really renders, so the cookies are committed first
    return this.redirectResponse(targetUri);
  }

  /**
   * Sends the browser on to the application.
   *
   * By default this is not a bare `302` but an actual - empty - HTML document
   * that carries the `Set-Cookie` headers and only then navigates on. A redirect
   * is a response the browser consumes internally: the identity cookies are
   * written while nothing is ever painted, and a prefetching browser, an
   * intermediary or a privacy mode that treats the hop as a tracking redirect can
   * drop them on the way. A rendered document is a page the browser commits as
   * the current top level document, so the cookies are stored before the
   * navigation to the application even starts.
   *
   * Set `BSAPI_INTERSTITIAL=false` to go back to the plain `302`, and
   * `BSAPI_INTERSTITIAL_DELAY_MS` to keep the page on screen for a moment.
   */
  private Response redirectResponse(URI targetUri) {
    if (!interstitialEnabled()) {
      return Response
          .status(Response.Status.FOUND)
          .location(targetUri)
          .build();
    }
    return Response
        .ok(this.interstitialPage(targetUri), TEXT_HTML_UTF_8)
        .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
        .header("Pragma", "no-cache")
        // this page's own URL still carries the access token, so it must not reach
        // the application as the `Referer` of the follow up navigation - unlike a
        // `302`, a document initiated navigation would send one
        .header("Referrer-Policy", "no-referrer")
        .build();
  }

  /**
   * A blank page whose only job is to exist for one paint and then replace itself
   * with the application. `location.replace` rather than an assignment, so the
   * token bearing URL does not end up in the browser history; the `meta refresh`
   * is the fallback for a browser without JavaScript.
   */
  private String interstitialPage(URI targetUri) {
    final long delayMs = interstitialDelayMs();
    final long refreshSeconds = (delayMs + 999L) / 1000L;
    final String href = escapeHtmlAttribute(targetUri.toString());
    return "<!DOCTYPE html>\n"
        + "<html lang=\"en\">\n"
        + "<head>\n"
        + "<meta charset=\"utf-8\">\n"
        + "<meta name=\"referrer\" content=\"no-referrer\">\n"
        + "<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + ";url=" + href + "\">\n"
        // a zero width space: an empty or missing title makes the browser label the
        // tab with the URL, and that URL carries the access token
        + "<title>&#8203;</title>\n"
        + "<style>html,body{height:100%;margin:0;background:#fff}#bs-continue{display:none}</style>\n"
        + "</head>\n"
        + "<body>\n"
        // the anchor carries the target, so the script below never has to embed a
        // URL into a JavaScript string literal
        + "<a id=\"bs-continue\" href=\"" + href + "\">Continue</a>\n"
        + "<noscript><a href=\"" + href + "\">Continue</a></noscript>\n"
        + "<script>\n"
        + "(function(){var t=document.getElementById('bs-continue').href;"
        + "window.setTimeout(function(){window.location.replace(t);}," + delayMs + ");})();\n"
        + "</script>\n"
        + "</body>\n"
        + "</html>\n";
  }

  private static boolean interstitialEnabled() {
    final String configured = System.getenv("BSAPI_INTERSTITIAL");
    return null == configured || !"false".equalsIgnoreCase(configured.trim());
  }

  /**
   * How long the blank page stays on screen before it navigates on. `0` - the
   * default - still renders the page, it just leaves it again as soon as it is
   * there. Capped, so a typo cannot strand the user on an empty page.
   */
  private static long interstitialDelayMs() {
    final String configured = System.getenv("BSAPI_INTERSTITIAL_DELAY_MS");
    if (null == configured || configured.trim().isEmpty()) {
      return 0L;
    }
    try {
      return Math.max(0L, Math.min(MAX_INTERSTITIAL_DELAY_MS, Long.parseLong(configured.trim())));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static String escapeHtmlAttribute(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  /**
   * Determines where to send the browser back to. Uses the `redirect_uri`
   * parameter, falling back to the `Referer` header, and always validates the
   * result against the `Valid redirect URIs` of the target client - otherwise
   * this endpoint would be an open redirect.
   */
  private URI resolveRedirectUri(String requestedRedirectUri, ClientModel client) {
    String candidate = requestedRedirectUri;
    if (null == candidate || candidate.isEmpty()) {
      // fall back to the page that redirected here - note that browsers send only
      // the bare origin for cross-origin navigations under the default
      // `Referrer-Policy: strict-origin-when-cross-origin`, and nothing at all on
      // an https -> http downgrade, so `redirect_uri` is the reliable way
      candidate = this.requestHeaders().getHeaderString("Referer");
    }
    if (null == candidate || candidate.isEmpty()) {
      throw new ErrorResponseException(Errors.INVALID_REDIRECT_URI,
          "No `redirect_uri` query parameter given and no `Referer` header to fall back to",
          Response.Status.BAD_REQUEST);
    }
    final String validatedRedirectUri = RedirectUtils.verifyRedirectUri(this.keycloakSession, candidate, client);
    if (null == validatedRedirectUri) {
      throw new ErrorResponseException(Errors.INVALID_REDIRECT_URI,
          "Redirect target does not match the `Valid redirect URIs` of client " + client.getClientId(),
          Response.Status.BAD_REQUEST);
    }
    // `verifyRedirectUri` hands back the value as it was supplied, so a caller that
    // encoded it twice would yield a still-encoded, non absolute string here. Sending
    // that as `Location` would make the browser resolve it against the Keycloak URL,
    // so refuse it instead of emitting a broken redirect.
    final URI location;
    try {
      location = new URI(validatedRedirectUri);
    } catch (URISyntaxException e) {
      throw new ErrorResponseException(Errors.INVALID_REDIRECT_URI, "Redirect target is not a valid URI",
          Response.Status.BAD_REQUEST);
    }
    if (!location.isAbsolute()) {
      throw new ErrorResponseException(Errors.INVALID_REDIRECT_URI,
          "Redirect target must be an absolute URL - do not URL encode it more than once",
          Response.Status.BAD_REQUEST);
    }
    return location;
  }

  private String getAccessControlAllowOrigin(String targetClient) {
    ClientModel newClient = this.getValidatedTargetClient(targetClient);
    // get referer
    String refererHeader = this.requestHeaders().getHeaderString("Referer");
    String referer;
    try {
      URL url;
      url = new URL(refererHeader);
      String protocol = url.getProtocol();
      String authority = url.getAuthority();
      referer = String.format("%s://%s", protocol, authority);
    } catch (MalformedURLException e) {
      referer = "";
    }

    // search for matching web origin
    for (String currentWebOrigin : newClient.getWebOrigins()) {
      if (currentWebOrigin.equals("*")) {
        // `*` not allowed when `Access-Control-Allow-Credentials` is `true`
        return referer;
      }
      if (currentWebOrigin.equalsIgnoreCase(referer)) {
        return referer;
      }
    }

    // fail with empty one
    return "";
  }

  private ClientModel getValidatedTargetClient(String targetClient) {
    final RealmModel realm = this.keycloakSession.getContext().getRealm();
    ClientModel newClient = this.keycloakSession.clients().getClientByClientId(realm, targetClient);
    if (null == newClient || !newClient.isPublicClient()) {
      throw new ErrorResponseException(Errors.CLIENT_NOT_FOUND, "Client not found or not public",
          Response.Status.BAD_REQUEST);
    }
    return newClient;
  }

  /**
   * Takes the access token from the `token` query parameter. The `Authorization`
   * header is still accepted as a fallback so that callers of the previous
   * XHR based flow keep working.
   */
  private AccessToken validateAccessToken(String tokenQueryParam) {
    String accessToken = tokenQueryParam;
    if (null == accessToken || accessToken.isEmpty()) {
      accessToken = this.bearerTokenFromAuthorizationHeader();
    }
    if (null == accessToken || accessToken.isEmpty()) {
      throw new ErrorResponseException(Errors.INVALID_TOKEN, "No `token` query parameter provided",
          Response.Status.UNAUTHORIZED);
    }
    final AccessToken token = Tokens.getAccessToken(accessToken, this.keycloakSession);
    if (token == null) {
      throw new ErrorResponseException(Errors.INVALID_TOKEN,
          "Invalid or expired access token" + this.issuerMismatchHint(accessToken),
          Response.Status.UNAUTHORIZED);
    }
    return token;
  }

  /**
   * By far the most common reason for a rejected token is an issuer mismatch.
   * Keycloak validates the token against the realm URL it derives from the
   * incoming request, so a token minted through a different address - an internal
   * server-to-server URL, or a proxy whose headers Keycloak is not configured to
   * trust - never verifies, no matter how valid it is. Spell that out rather than
   * leaving the caller with a bare "invalid token".
   */
  private String issuerMismatchHint(String accessToken) {
    final String expectedIssuer = Urls.realmIssuer(
        this.keycloakSession.getContext().getUri().getBaseUri(),
        this.keycloakSession.getContext().getRealm().getName());
    final String tokenIssuer;
    try {
      tokenIssuer = TokenVerifier.create(accessToken, AccessToken.class).getToken().getIssuer();
    } catch (VerificationException e) {
      return "";
    }
    if (null == tokenIssuer || expectedIssuer.equals(tokenIssuer)) {
      return "";
    }
    return String.format(
        " - issuer mismatch: the token was issued by `%s`, but this endpoint validates against `%s`."
            + " Obtain the token through the same public Keycloak URL the browser uses, and make sure"
            + " `hostname` and `proxy-headers` are configured so Keycloak sees its public address.",
        tokenIssuer, expectedIssuer);
  }

  /**
   * `KeycloakContext.getRequestHeaders()` is deprecated as of Keycloak 26; the
   * headers are reached through the `HttpRequest` instead.
   */
  private HttpHeaders requestHeaders() {
    return this.keycloakSession.getContext().getHttpRequest().getHttpHeaders();
  }

  private String bearerTokenFromAuthorizationHeader() {
    final HttpHeaders headers = this.requestHeaders();
    final String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
    if (authorization == null) {
      return null;
    }
    final String[] authorizationParts = authorization.split(" ");
    if (authorizationParts.length != 2 || !authorizationParts[0].toLowerCase().equals("bearer")) {
      throw new ErrorResponseException(Errors.INVALID_TOKEN, "Malformed access token", Response.Status.UNAUTHORIZED);
    }
    return authorizationParts[1];
  }
}
