# Local platform-administrator token for Postman

Development-only helper: `infra/keycloak/platform_admin_login.py`.
This is not a production OAuth client, authentication server or token service.
It makes no Docker/database changes and never reads `.env.keycloak.local`.

## Prerequisites

- Python 3, OpenSSL on PATH, and a local graphical browser on macOS/Linux (POSIX).
- The existing Keycloak instance is already available at `http://localhost:8180`.
  Use the separately documented identity setup if it is not running; this helper
  does not start it or change credentials, clients, roles or the realm.
- A human development user exists in realm `payflow`, with client role
  **payflow-merchant-api / platform-admin**. A master-realm bootstrap administrator,
  merchant-admin or service-account token is not sufficient. An authorized
  administrator must provision that human/role privately if missing; the realm
  import deliberately seeds no human users.
- The existing console client remains public, code-flow/S256 enabled and service
  accounts disabled. Its optional scope `merchant.bindings.manage` is available.
  Remember that restart/import does not update a persisted realm automatically.

The exact local contract is fixed in the helper:

| Setting | Value |
| --- | --- |
| Issuer | http://localhost:8180/realms/payflow |
| Authorization endpoint | http://localhost:8180/realms/payflow/protocol/openid-connect/auth |
| Token endpoint | http://localhost:8180/realms/payflow/protocol/openid-connect/token |
| Client ID | payflow-platform-console |
| Client secret | None |
| Redirect | http://localhost:8182/callback |
| Scope | openid merchant.bindings.manage |
| PKCE | S256 |
| Access audience | payflow-merchant-api |
| API client role | platform-admin |

No Postman callback is added to Keycloak. The existing generic browser_login.py
remains unchanged: its console flow requests a different scope and discards
its tokens. Use this dedicated helper for membership-administration testing.

## Execute

From the repository root, check callback-port availability without stopping its
owner:

```sh
lsof -nP -iTCP:8182 -sTCP:LISTEN
```

If occupied, stop this procedure and coordinate with the owner. Do not terminate
another application or run the generic browser helper on the same port.

For validation only, with no token file:

```sh
python3 -B infra/keycloak/platform_admin_login.py
```

For an explicitly requested Postman export:

```sh
python3 -B infra/keycloak/platform_admin_login.py --export-for-postman
```

The helper binds **127.0.0.1:8182 and ::1:8182** (IPv6-only socket) and starts
serving callbacks before launching the browser. Both bindings are loopback-only;
the registered redirect stays **http://localhost:8182/callback**. Do not replace
localhost with an IP literal in the OAuth URL or token exchange. If IPv6 loopback
is disabled, it explicitly reports IPv4-only operation. A port conflict on either
available address family fails before launching the browser. It generates fresh
state/nonce/verifier and opens Keycloak's own login page. Enter the human user's
password only there. Complete the login within 180 seconds. A callback tab reports
only a generic result; no tokens or authorization codes are displayed or logged by
the listener. Invalid/forged callback requests are rejected without completing
login. The listener remains active throughout browser login and closes after a valid
callback, denial, timeout, browser failure or cancellation. Browser launch runs
on a daemon thread: even a launcher that blocks while loading the callback cannot
block the callback server or suspend the login deadline. Browser windows are not
closed automatically; a callback from a timed-out session must not be reused. Token/JWKS network requests have 15-second timeouts; local HTTP
requests use an idle timeout as well. A stalled local client can delay completion;
this small development listener is not a hardened production web server.

Before accepting the access token, it verifies:

- RS256 signature against the fixed local issuer JWKS (existing OpenSSL verifier).
- Exact issuer, Merchant API audience, console azp and access-token type.
- Required subject, finite issued-at/expiry, time validity and not-before if present.
- Both requested scopes and the Merchant API platform-admin role.
- Signed ID token for the console client, matching nonce and subject.

Code exchange and JWKS fetch use only the fixed local endpoints, no environment
proxy and no HTTP redirects. Login does not use a client secret or password grant.
No token/refresh token/password/diagnostic is printed to the terminal. Failure
messages identify the stage and, for HTTP failures, only the numeric status.
They never include private server response details, request URLs, callback
parameters or exception text. Invalid callback reasons are static categories and
are reported at most once per category while the listener continues waiting.

Without the export flag, tokens stay in memory and are discarded when the helper
exits. With the flag, **only the access token** is written to:

```text
.local-private/platform-admin-access-token.txt
```

Export directory permissions and existing output are checked before browser
login; the final write remains exclusive in case anything changes during login.
The ignored directory is created with mode 0700, and the new file with mode 0600.
The directory must be owned by the current user and already private if it exists.
Directory symlinks, existing output files and file symlinks are rejected. No file
is overwritten. Refresh and ID tokens are never exported. No pre-existing private
file is automatically deleted or changed. The helper may remove only its own
incomplete newly created output if writing fails.

If a prior export exists, remove only that generated token file after you finish
using it, using the cleanup command below, then rerun. Do not regenerate or edit
Keycloak credentials to solve an export error.

## Copy into Postman yourself

1. Open the generated file privately in a trusted local plain-text editor. Do not
   use `cat`, `echo`, terminal screenshots, a cloud editor or an online JWT decoder.
2. Copy its single token line into Postman Desktop's **local sensitive variable**
   `platform_admin_token`, or a suitable local Vault entry. Do not put it in any
   tracked Postman environment/collection. Masking a variable is not protection
   against exporting or synchronizing it: keep this value unshared and unsynced.
3. On the request select **Bearer Token**, value `{{platform_admin_token}}`. Do not
   use the Postman OAuth callback, automatic refresh or inherited authentication.
4. Remove any Origin header, use the local Desktop Agent, and avoid the Postman
   console, shared screenshots and exporting response/request history with tokens.

Example existing API request (replace the UUID with an existing Merchant UUID):

```text
GET http://localhost:8086/api/v1/merchants/<merchant-uuid>/memberships?page=0&size=20
Accept: application/json
Authorization: Bearer {{platform_admin_token}}
```

Expected 200 for an existing merchant; an unknown merchant yields generic 404
only after successful administrator authorization. 401 indicates token validation
failed (for example expiry). 403 indicates missing capability or a route that
remains denied. Merchant Service must be running with its required migrations,
including 005; the helper does not start it or migrate any database. Do not run
this procedure against production.

The token expires in about five minutes under the current realm contract.
Re-run the full interactive helper for a fresh token; do not store refresh tokens.
All other business routes remain governed by the existing deny-by-default policy.

## Cleanup

After copying and completing the test, remove only the helper-generated file:

```sh
rm -- .local-private/platform-admin-access-token.txt
```

Clear the Postman local variable/Vault value and clipboard/history according to
your local tooling. Log out of the local Keycloak session when done. Deleting the
file does **not** revoke an already issued access token or erase editor/clipboard
copies; it remains usable until expiry. Ordinary deletion is not guaranteed
secure erasure on SSDs. Keep backups/cloud sync away from private token artifacts.

The loopback listener has already closed when the command exits. Do not stop
unrelated services/containers or delete databases/volumes during cleanup.

This is plaintext local development export, not encrypted secret storage or a
production security guarantee. Other processes running as your user, root and
filesystem backups may access it. Prefer the validation-only mode unless you
actually need Postman access.

## Automated checks

```sh
python3 -B -m unittest discover -s infra/keycloak/tests -v
git diff --check
```

Tests use synthetic callbacks on ephemeral loopback ports and ephemeral RSA test
keys; they never contact real Keycloak, open a real browser, read credentials or
operate Docker. Coverage includes RFC7636 PKCE, fresh randomness, callback
state/issuer/host checks, duplicate parameters and OAuth errors, actual RSA
verification/claims, invalid roles/scope/nonce, timeouts/port conflicts, explicit
export permission, private file modes, symlink/no-overwrite protections and
sanitized failure output.

## Troubleshooting stage diagnostics

| Stage | Check without exposing private values |
| --- | --- |
| listener-bind | Check IPv4 and IPv6 listeners on port 8182; coordinate with their owners. |
| browser-open | Check the local default browser/launcher. No authorization URL is printed as a fallback. |
| callback-timeout | Check the registered localhost redirect and complete a new login within the deadline. |
| callback-denied | Inspect the Keycloak page/configuration privately; the helper never echoes OAuth error descriptions. |
| token-exchange | Check Keycloak reachability, exact redirect/client/PKCE settings, and code expiration/single-use. |
| jwks-fetch | Check the local Keycloak public JWKS endpoint; redirects and proxies remain disabled. |
| access-validation / access-claims | Check signing keys, issuer/audience/client, token type and clock/claim validity. |
| scope-validation | Verify console optional scope and request openid merchant.bindings.manage. |
| role-validation | Verify the human user's Merchant API platform-admin client role. |
| id-validation / nonce-validation | Retry a fresh login; never mix codes/verifiers/tokens from different sessions. |
| export-exists | Finish using and remove only your generated token file; it is never overwritten. |
| export-permissions / export-preflight / export-write | Check private-directory ownership/mode, symlinks, filesystem access and free space. |
| cancelled / internal | Cancellation closes listeners; unexpected failures display no raw diagnostics. |

The prior blanket error did not distinguish these stages. Its socket was bound
before browser launch, but callback servicing began only after the synchronous
launcher returned. It also listened only on IPv4 despite a localhost redirect.
The regression tests now reproduce blocking launch/callback overlap, delayed
login, bounded timeout, both IP families and IPv6 conflict cleanup. These are
confirmed defects; they do not prove which stage caused an earlier real login to
fail. A new interactive run is required to identify that exact live failure.

## Confirmed access-validation failure — 2026-11-12

A real validation-only console login reproduced **access-subject-missing** after
successful callback and code exchange. No JWT or claim values were displayed or
persisted. Signature, issuer, Merchant audience, authorized client and token type
checks completed before this failure. The old combined access-validation message
concealed the missing required identity. Time/role/scope/ID-token checks occur
later, so full acceptance still needs a fresh login after the issuer repair.

The helper issuer and Merchant API default issuer are the same configured local
issuer. Both require payflow-merchant-api audience. The existing explicit Audience
mapper on merchant.bindings.manage already supplies that audience; it was present
in the live token. No new audience mapper or relaxed audience validator is needed
for this observed failure.

The missing subject is an issuer configuration issue: the imported interactive
clients have empty default scopes (no basic scope) and originally had no Subject
mapper. Keycloak moved access-token subject emission into its Subject (sub)
protocol mapper, normally supplied by basic. See the official
[upgrade guide](https://www.keycloak.org/docs/26.8.0/upgrading/#sub-claim-is-added-to-access-token-via-protocol-mapper)
and [26.8.2 Subject mapper implementation](https://github.com/keycloak/keycloak/blob/26.8.2/services/src/main/java/org/keycloak/protocol/oidc/mappers/SubMapper.java).

The checked-in realm now adds only the native oidc-sub-mapper directly to the
console and portal clients, emitting the authenticated user's subject in access
tokens and introspection. The same omission affected both human clients. No
permissions, client scopes, user records, roles, credentials, redirects or PKCE
settings are changed. Subject is never inferred from email, another token or a
request header. Merchant API and helper subject requirements remain enforced.

### Additive repair for the existing running realm (manual)

Changing the import file does not update the existing realm. Do not delete users,
replace the realm, reset its database/volume, or re-import with override.

Using an authorized administrator in the existing local administration console:

1. Select realm payflow, then Clients → payflow-platform-console.
2. Open Client scopes → payflow-platform-console-dedicated → Mappers (the dedicated
   scope represents the client's own mappers, not an optional permission scope).
3. Choose Configure a new mapper / Add mapper by configuration → **Subject (sub)**.
4. Set Name **subject**, Add to access token **ON**, Add to token introspection
   **ON**; save. Provider ID must be **oidc-sub-mapper**. Use the native Subject
   mapper, not a hardcoded claim, email/user-attribute mapper, or copied ID-token
   value. If an equivalent mapper already exists, verify its settings instead of
   creating a duplicate. Keep existing non-lightweight access-token behavior.
5. Apply the same additive mapper to payflow-portal's dedicated scope when using
   its browser tokens. Do not alter the machine client or any existing users.
6. Obtain a fresh code/token by rerunning the helper WITHOUT export first. Old
   tokens cannot be repaired in place. A server restart is not needed for this
   ordinary mapper configuration change.

The audience path should also remain as-is:
Client scopes → merchant.bindings.manage → Mappers → Audience, with Included client
 audience payflow-merchant-api, Add to access token ON and Add to ID token OFF.
The console must retain that scope as Optional, and the human must retain the
Merchant API platform-admin client role. Keycloak may omit a role-restricted scope
and its mappers when the user lacks that role; see
[client-scope permissions](https://www.keycloak.org/docs/26.8.0/server_admin/#_client_scopes_permissions).
If a future attempt reports access-audience, check these persisted settings
explicitly rather than enabling full-scope access or weakening validation.

### Safe per-condition identifiers

The shared verifier now distinguishes format, signing-key, algorithm, signature,
issuer, audience, client, token-type, subject-missing/subject and timestamp checks.
The console helper prefixes those static identifiers with access- or id-, such as
access-audience, access-client, access-time-expired and id-issuer. Signature is
verified before claim-specific diagnostics are produced. No received claim value,
JWT, authorization code, HTTP body, credential or exception text is logged.
Transport, role, scope and nonce failures retain their separate safe stages.
The verifier also explicitly rejects missing/malformed timestamps and subjects;
no requirement was removed to obtain a token.

The running realm was NOT modified automatically during this investigation.
Apply the additive change above, then run:

```sh
python3 -B infra/keycloak/platform_admin_login.py
# Only after validation succeeds, explicitly obtain a fresh token for Postman:
python3 -B infra/keycloak/platform_admin_login.py --export-for-postman
```
