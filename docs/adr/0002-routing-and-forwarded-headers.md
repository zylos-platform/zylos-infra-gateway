# ADR 0002: Routing and Forwarded Headers

- **Status:** Accepted
- **Date:** 2026-05-29
- **Relates to:** ADR 0001 (gateway baseline)

## Context

The gateway must route ingress requests to internal services. The first route
targets the reference internal service (`zylos-internal-hello`). This ADR
records the routing conventions and the forwarded-header posture.

## Decision

**Routes are externalized in YAML** (`spring.cloud.gateway.server.webflux.routes`),
not Java `RouteLocator` beans. YAML routes are overridable per-environment via
ConfigMap/env without rebuilding the image — the GitOps-friendly choice. Route
target URIs are env-driven (`ZYLOS_HELLO_SERVICE_URI`) so the same image runs
against kind and production.

**Path-based predicate, full path forwarded.** The route matches
`Path=/api/v1/hello/**` and forwards the full path (no `StripPrefix`).
Internal services own their `/api/v1/<domain>/**` path space; the gateway does
not rewrite it.

**Forwarded headers trusted only from in-cluster proxies.** Spring Cloud 2025.x
disables X-Forwarded-* and Forwarded headers by default. We re-enable them by
setting `trusted-proxies` to a regex matching the cluster's internal range
(default kind pod CIDR `10.244.*`; override per-environment). This lets internal
services see the original client host/scheme while rejecting spoofed forwarded
headers from outside the trusted range.

**Cookies stripped at ingress.** A `RemoveRequestHeader=Cookie` default filter
ensures no browser cookie is ever forwarded to an internal service. The gateway
and internal services are Bearer-token based; cookies belong only to the
browser-facing BFFs, never downstream.

## Boundary: raw token forwarded until future PR

Until the token-exchange filter PR lands, the gateway forwards the raw
ingress token (`aud: zylos-gateway`) to the backend. A real internal service
expecting `aud: zylos-internal-*` would reject it. This is acceptable now
because no internal service is deployed yet; Future PR replaces the forwarded
Authorization header with a per-service-audience exchanged token (and emits
the single-hop `act` claim via the ActClaimMapper).

## Trade-offs Accepted

- **One route for now.** Additional routes are added as internal services come
  online. The pattern (Path predicate + env-driven URI) is established here.

- **trusted-proxies as a regex.** A regex is coarser than an explicit allowlist
  but matches Spring Cloud's model. Production should narrow it to the mesh's
  actual egress range rather than a broad `10.*`.

- **No StripPrefix.** Forwarding the full path keeps the gateway dumb about
  internal routing and avoids prefix-rewrite bugs, at the cost of services and
  gateway sharing a path convention.

## Verification

`HelloRouteIT` runs a live server proxying to a WireMock backend, with a
WireMock Keycloak for real token validation:

- unauthenticated request → 401, backend not called;
- valid token → proxied, backend returns 200;
- forwarded headers present for a trusted proxy;
- wrong-audience token → 401;
- Cookie header stripped before forwarding.

## References

- ADR 0001 (gateway baseline)
- Forwarded headers
  filter: <https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/httpheadersfilters.html>
- Spring Cloud 2025.0 release notes (X-Forwarded default change)
