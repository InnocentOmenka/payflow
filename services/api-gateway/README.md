# API Gateway — Step 1

Minimal Spring Cloud Gateway Server Web MVC application on Java 21 and Spring
Boot 4.1.2. The gateway-local Spring Cloud 2025.1.4 BOM selects Gateway 5.0.4;
other services' dependency management is unchanged. Spring's
[compatibility matrix](https://spring.io/projects/spring-cloud/) supports Boot
4.1.x with Cloud 2025.1.2 onward. The servlet
[Gateway starter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/starter.html)
allows reuse of the existing shared servlet resource-server security.

There are **no proxy routes**. Exact `GET /actuator/health` is anonymous and returns
minimal health status. All other requests are denied, including Payment, Merchant,
internal paths, other Actuator paths and non-GET methods. Missing/rejected bearer
authentication returns 401; an otherwise valid token returns 403. A valid token
does not grant routing permission. Invalid bearer credentials are rejected even
on the health path. No Basic/form login, session authentication or browser origin
allowance is introduced.

The application imports `FailClosedResourceServerConfiguration` unchanged. This
reuses trusted JWKS/RS256, issuer, audience, timestamps and access-token claim
validation, plus the existing sanitized JSON errors and stateless setup.

## Build and run

From the repository root:

```sh
mvn -pl services/api-gateway -am package
java -jar services/api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar
```

This starts a new gateway only. It does not start or configure existing services,
Keycloak, databases or Kafka. Check the selected port is free before starting it.
No Keycloak contact is needed to start or serve health; token validation fetches
the configured JWKS when required. No credentials or local `.env` files are read.

| Environment variable | Default |
| --- | --- |
| `GATEWAY_SERVER_PORT` | `8080` |
| `PAYFLOW_JWT_ISSUER` | `http://localhost:8180/realms/payflow` |
| `PAYFLOW_JWT_JWK_SET_URI` | `http://localhost:8180/realms/payflow/protocol/openid-connect/certs` |
| `PAYFLOW_GATEWAY_JWT_AUDIENCE` | `payflow-payment-api` |

Standard Boot configuration, including `SERVER_ADDRESS`, remains available.
The existing Payment audience anticipates later Payment routes without adding a
new Keycloak client/audience or widening any existing service's authorization.
For container deployment, supply reachable JWKS and trusted issuer settings;
localhost defaults are for development. Keep exact issuer validation enabled.

## Focused tests

```sh
mvn -pl services/api-gateway -am test
```

Tests start the actual embedded servlet server on a random loopback port and use
ephemeral RSA keys/JWKS. They require neither Docker nor a running Keycloak.
They verify minimal anonymous health, one security chain, empty Gateway route
configuration, anonymous denial, valid-token denial, malformed/expired/wrong
issuer/audience/signature/missing-subject rejection, method restrictions, spoofed
identity headers and stateless error responses. A test-only internal handler must
never execute, demonstrating denial before handler dispatch.

Payment proxying, discovery, circuit breakers, rate limiting, Kubernetes manifests
and additional filters are intentionally outside Step 1.

## Validation — 2026-11-12

`mvn -pl services/api-gateway -am package` passed 87 tests with zero failures,
errors or skips: shared security 59 and Gateway 28. Cloud's compatibility verifier
remained enabled. The executable jar contains Gateway Web MVC 5.0.4 and no
Spring WebFlux dependency. A separate jar smoke test on a random loopback port
confirmed health 200 and Payment/internal path 401 with an unreachable test JWKS
address; only that smoke-test process was stopped. No live services or databases
were changed. Whitespace checks passed. No compatibility issues were observed.
