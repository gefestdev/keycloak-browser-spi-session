package com.contabo.keycloak.spi.realmresourceprovider.browseresssion;

import org.jboss.resteasy.reactive.NoCache;
import org.keycloak.authorization.util.Tokens;
import org.keycloak.common.ClientConnection;
import org.keycloak.events.Errors;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.ErrorResponseException;
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

    // finally send the browser back to the application it came from
    return Response
        .status(Response.Status.FOUND)
        .location(targetUri)
        .build();
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
      throw new ErrorResponseException(Errors.INVALID_TOKEN, "Invalid or expired access token",
          Response.Status.UNAUTHORIZED);
    }
    return token;
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
