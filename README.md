# keycloak-spi-browser-session-api

![Keycloak 21.x](https://img.shields.io/badge/Keycloak-21.x-blue)
![Java 11+](https://img.shields.io/badge/Java-11%2B-orange)
![License Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green)

A [Keycloak](https://www.keycloak.org/) [SPI](https://www.keycloak.org/docs/latest/server_development/index.html#_providers)
that turns a valid access token (JWT) into a **real browser session**: it creates a user
session in Keycloak, sets Keycloak's identity cookies and redirects the browser back to
your application.

Useful to bridge SSO from a legacy web application - which already holds a token but has
never performed an interactive login in this browser - to a new application that is
protected by Keycloak (e.g. via [keycloak.js](https://www.npmjs.com/package/keycloak-js)).

> **Prerequisite:** the legacy application must be able to obtain a valid access token for
> the realm. Without it there is nothing to convert.

## Table of contents

- [How it works](#how-it-works)
- [Compatibility](#compatibility)
- [Build](#build)
- [Deployment](#deployment)
- [Configuration](#configuration)
- [API](#api)
- [Usage](#usage)
- [Security considerations](#security-considerations)
- [Local demo](#local-demo)
- [Troubleshooting](#troubleshooting)
- [License](#license)

## How it works

The endpoint is entered by a **top level browser navigation**. The application sends the
browser to the SPI, the SPI creates the session and sets the cookies, and then redirects
the browser back:

```
application  --(302, ?token=...)-->  SPI  --(302 + Set-Cookie)-->  application
```

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as Legacy app
    participant K as Keycloak + SPI
    participant D as Protected app

    A->>B: window.location = /browser-session/init?token=...
    B->>K: GET /realms/{realm}/browser-session/init
    K->>K: validate publicClient (must exist & be public)
    K->>K: validate token (signature, expiry, realm)
    K->>K: validate redirect target against client's redirect URIs
    K->>K: load user by `sub`, must exist & be enabled
    K->>K: create UserSession + ClientSession
    K-->>B: 302 Found + Set-Cookie (Keycloak identity cookies)
    B->>D: follow redirect - now an authenticated SSO session exists
```

Step by step, as implemented in
[`BrowserSessionRestProvider`](src/main/java/com/contabo/keycloak/spi/realmresourceprovider/browseresssion/BrowserSessionRestProvider.java):

1. `publicClient` is looked up in the realm and must exist **and be a public client**.
2. The token is validated (signature, expiry, realm) via `Tokens.getAccessToken`.
3. The redirect target is resolved and validated **before** any state is created, so a bad
   request never leaves a dangling user session behind.
4. The user is loaded by the token's `sub` claim and must exist and be enabled.
5. A `UserSessionModel` (auth method `KEYCLOAK`, persistent, remember-me off) and a client
   session for `publicClient` are created.
6. `AuthenticationManager.createLoginCookie` sets Keycloak's identity cookies.
7. The response is `302 Found` pointing at the redirect target.

## Compatibility

| SPI version | Keycloak      | Distribution | Java |
|-------------|---------------|--------------|------|
| 2.0         | 21.x          | Quarkus      | 11+  |
| 1.0         | 14.x (legacy) | WildFly      | 8    |

The jar is compiled to **Java 11 bytecode**, so it loads on both Java 11 and Java 17
Keycloak runtimes. Do not raise `java.version` in [pom.xml](pom.xml) above the Java version
your Keycloak server actually runs on - a Java 17 build fails on a Java 11 server with
`UnsupportedClassVersionError`. Check with `java -version` on the server, or in the
container: `docker exec <container> java -version`.

Keycloak 21 is still built on the `javax.ws.rs` namespace - the Jakarta EE namespace
migration only happened in Keycloak 22, so this artifact does **not** work on Keycloak 22
or newer without changing the imports and dependencies.

Keycloak 17+ no longer serves its endpoints under the `/auth` prefix, so the path is
`/realms/...` and not `/auth/realms/...`. If you run the server with
`--http-relative-path=/auth` for backwards compatibility, keep the old prefix.

## Build

```sh
mvn clean package
```

Produces `target/keycloak-spi-browser-session-api-2.0.jar`. All Keycloak and RESTEasy
dependencies are `provided` - the jar contains only the two SPI classes and the
`META-INF/services` registration.

## Deployment

The Quarkus based distribution (Keycloak 17+) has no `deployments` folder any more.
Providers are placed in `providers` and the server has to be re-augmented once:

1. Build with `mvn clean package`
2. Copy `target/keycloak-spi-browser-session-api-2.0.jar` to `/opt/keycloak/providers/`
3. Run `/opt/keycloak/bin/kc.sh build`
4. Start Keycloak

When building a container image, do all of that at image build time - see
[Dockerfile.keycloak](Dockerfile.keycloak):

```dockerfile
COPY target/keycloak-spi-browser-session-api-*.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
```

`kc.sh start-dev` re-augments automatically, so for local development dropping the jar into
`providers` and restarting is enough.

> The [Dockerfile](Dockerfile) in the repository root only ships the built jar into an
> internal base image and is not needed to run the SPI.

## Configuration

The SPI itself has no provider configuration. Everything is driven by the request
parameters and by Keycloak's own client settings.

| What | Where |
|------|-------|
| Realm | path element of the endpoint URL |
| User | taken from the `sub` claim of the token |
| Target client | `publicClient` query parameter - must be a **public** client in that realm |
| Allowed redirect targets | `Valid redirect URIs` of `publicClient` |
| CORS origin (preflight only) | `Web origins` of `publicClient` |

The only environment variable is:

| Variable | Default | Description |
|----------|---------|-------------|
| `BSAPI_ACCESS_CONTROL_ALLOW_HEADERS` | `origin, content-type, accept, authorization` | Overrides `Access-Control-Allow-Headers` on the `OPTIONS` preflight response |

Note that the redirect target is validated against the `Valid redirect URIs` of
`publicClient` using the same check Keycloak applies to OIDC redirects (wildcards
supported). **The URL you want to come back to has to be listed on that client**, even when
the returning page belongs to a different application.

## API

| Method    | Path                                             | Description |
|-----------|--------------------------------------------------|-------------|
| `GET`     | `{baseUrl}/realms/{realm}/browser-session/init`   | Creates the browser session, answers `302 Found` with the session cookies |
| `OPTIONS` | `{baseUrl}/realms/{realm}/browser-session/init`   | CORS preflight, kept for the legacy XHR flow |

### Query parameters

| Parameter      | Required | Description |
|----------------|----------|-------------|
| `publicClient` | yes      | public client (defined in Keycloak) for which to start the browser session |
| `token`        | yes\*    | the access token (JWT). \*An `Authorization: Bearer` header is accepted as a fallback |
| `redirect_uri` | no       | **absolute** URL to redirect back to. Falls back to the `Referer` header. Must match the `Valid redirect URIs` of `publicClient` |

Do not URL-encode `redirect_uri` more than once: the value is handed back as supplied, and
a still-encoded, non absolute string is rejected rather than turned into a broken redirect.

The `Referer` fallback is unreliable - under the default
`Referrer-Policy: strict-origin-when-cross-origin` browsers send only the bare origin on
cross-origin navigations, and nothing at all on an `https` -> `http` downgrade. Pass
`redirect_uri` explicitly.

### Responses

| Status             | `error`                | When |
|--------------------|------------------------|------|
| `302 Found`        | -                      | success - `Location` points at the redirect target, cookies are set |
| `400 Bad Request`  | `client_not_found`     | `publicClient` is unknown or is not a public client |
| `400 Bad Request`  | `invalid_redirect_uri` | no `redirect_uri` and no `Referer` to fall back to; target not listed on the client; target not an absolute URL |
| `401 Unauthorized` | `invalid_token`        | no token given, malformed `Authorization` header, or invalid/expired token |
| `401 Unauthorized` | `user_not_found`       | the token's subject is unknown in the realm or the user is disabled |

Errors are returned as Keycloak's standard OAuth error body:

```json
{ "error": "invalid_redirect_uri", "error_description": "..." }
```

### CORS

Only the `OPTIONS` preflight emits CORS headers. The allowed origin is derived from the
`Referer` header and matched against the `Web origins` of `publicClient`; a configured `*`
echoes the request origin back, because `*` is not allowed together with
`Access-Control-Allow-Credentials: true`.

The `GET` response carries **no** CORS headers, so it cannot be consumed by a cross-origin
`fetch`/`XHR` - use the top level navigation described below.

## Usage

Prerequisite is a valid access token obtained from your Keycloak IDM.

```javascript
// config
var providerUrl = "https://your.keycloak.example.org";
var realm = "master";
var targetClientForNewSession = "application";
var jwt = "eyJhbGciOiJSUzI1NiIs.....";
var redirectUri = window.location.href; // where to come back to

// invocation - top level navigation, the SPI redirects back here
window.location.href = `${providerUrl}/realms/${realm}/browser-session/init`
  + `?publicClient=${encodeURIComponent(targetClientForNewSession)}`
  + `&token=${encodeURIComponent(jwt)}`
  + `&redirect_uri=${encodeURIComponent(redirectUri)}`;
```

## Security considerations

This endpoint mints a browser session out of a bearer token. Read this section before
deploying it.

* **The token travels in the URL.** A browser redirect cannot carry an `Authorization`
  header, so the token lands in the browser history, in the Keycloak access log and in the
  logs of every reverse proxy in front of it. Use short lived access tokens.
* **HTTPS is required.** Keycloak sets its identity cookies with `SameSite=None`, which
  forces the `Secure` attribute. Browsers therefore drop those cookies over plain `http://`
  everywhere except `localhost`.
* **The token is not bound to `publicClient`.** Validation covers signature, expiry and
  realm - not the `azp`/`aud` claims. Any valid access token of the realm can therefore be
  exchanged for a browser session on any public client of that realm. Treat every token in
  the realm as equally powerful, and only expose this endpoint where that is acceptable.
* **No open redirect.** The redirect target is verified against the client's
  `Valid redirect URIs`, so the endpoint cannot be used to bounce users to arbitrary URLs.
* **The new session is independent.** It is not linked to the session the original token
  came from, so logging out on the legacy side does not terminate it. Its lifetime follows
  the realm's SSO session settings.
* **No re-authentication happens.** Anyone able to read a token - from a log, a shared
  screen, a copied URL - can obtain a full browser session with it until it expires.

## Local demo

Starts Keycloak 21 with PostgreSQL plus two static demo apps. The `keycloak` service is
built from [Dockerfile.keycloak](Dockerfile.keycloak) and bakes in the jar from `target`,
so build the SPI first:

```sh
mvn clean package
docker-compose up --build
```

| Service | URL | Role |
|---------|-----|------|
| Keycloak | <http://localhost:5080> | IDM, `admin` / `admin` |
| `start-app` | <http://localhost:5082> | starting point - holds a JWT, no authentication of its own |
| `dest-app` | <http://localhost:5081> | demo site secured with `keycloak.js` |

The goal is to initiate a browser session for `dest-app` from `start-app`, having nothing
but a valid JWT.

### Configure Keycloak

Log in at <http://localhost:5080/> (`admin` / `admin`) and create:

* client `dest-app` - `Client authentication` **off** (public)
  * `Valid redirect URIs`: `http://localhost:5081/*` **and** `http://localhost:5082/*`
    (the demo returns to `start-app`, and the redirect target is checked against this
    client)
  * `Web origins`: `http://localhost:5082` and/or `*`
* client `private` - `Client authentication` **on** (confidential), `Direct access grants`
  enabled
* user `testuser` with password `Test123!`, `Email verified` on and no required actions, so
  the password grant succeeds

### Run it

Generate an access token and place it into [demo/start-app/index.html](demo/start-app/index.html).
This is for demo purposes only - never hardcode tokens in a real application.

```sh
CLIENT_ID=private
# change CLIENT_SECRET accordingly - it is generated by Keycloak
CLIENT_SECRET=8b3576db-6492-4238-aa60-d7c71a9663f7
API_USER=testuser
API_PASSWORD='Test123!'

ACCESS_TOKEN=$(curl -s \
  -d "client_id=$CLIENT_ID" \
  -d "client_secret=$CLIENT_SECRET" \
  --data-urlencode "username=$API_USER" \
  --data-urlencode "password=$API_PASSWORD" \
  -d 'grant_type=password' \
  'http://localhost:5080/realms/master/protocol/openid-connect/token' | jq -r '.access_token')

sed -i -E 's#var jwt = ".*$#var jwt = "'"$ACCESS_TOKEN"'";#' demo/start-app/index.html
```

Reload <http://localhost:5082>, click *Init Browser Session*, then *Open dest-app* - it
should come up authenticated without a login form.

After rebuilding the SPI, rebuild and restart the Keycloak container so that `kc.sh build`
picks up the new jar:

```sh
mvn clean package && docker-compose up -d --build keycloak
```

## Troubleshooting

| Symptom | Cause |
|---------|-------|
| `404` on `/realms/{realm}/browser-session/init` | jar not in `providers/`, or `kc.sh build` was not re-run after adding it. Check *Realm settings -> Provider info* in the admin console |
| `UnsupportedClassVersionError` on startup | jar compiled for a newer Java than the server runs. Lower `java.version` in [pom.xml](pom.xml) |
| Redirect works but the user is still not logged in | cookies were dropped - identity cookies are `Secure`, so plain `http://` only works on `localhost` |
| `400 invalid_redirect_uri` | the return URL is not listed in `Valid redirect URIs` of `publicClient`, or it was encoded twice and is no longer absolute |
| `400 client_not_found` | `publicClient` does not exist in that realm, or `Client authentication` is on (confidential client) |
| `401 invalid_token` | token expired, signed by another realm, or not passed at all |
| Provider does not load on Keycloak 22+ | Keycloak 22 moved to the Jakarta namespace; this build targets `javax.ws.rs` |
| Old `/auth/realms/...` URL returns `404` | Keycloak 17+ dropped the `/auth` prefix unless started with `--http-relative-path=/auth` |

## License

[Apache License, Version 2.0](LICENSE)
