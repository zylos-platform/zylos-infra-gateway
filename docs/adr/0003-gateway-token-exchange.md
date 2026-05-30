# ADR 0003: Gateway-Side Token Exchange

- **Status:** Accepted
- **Date:** 2026-05-30
- **Relates to:** ADR 0001 (baseline), ADR 0002 (routing); gitops ADR 0011/0013; starter ADR 0002/0003

## Context

Ingress tokens are minted for `aud: zylos-gateway`. Internal services validate
`aud == self` (`zylos-internal-*`) and reject anything else. The gateway must
therefore exchange the ingress token for a per-service-audience token before
forwarding — and that exchange is what produces the single-hop `act` claim
(gateway as actor) via the Keycloak ActClaimMapper, closing the delegation loop
designed.

## Decision

A `TokenExchange` gateway filter, applied per route with a target `audience`
argument, performs RFC 8693 exchange and replaces the forwarded
`Authorization` header with the exchanged token.

- **Runs after security.** The filter reads the validated `JwtAuthenticationToken`
  from `exchange.getPrincipal()`. The ingress token is already proven (signature,
  `iss`, `aud == zylos-gateway`, `exp`).
- **Exchange via the gateway's confidential client.** `client_id = zylos-gateway`
  authenticates the exchange; Keycloak records it as the actor.
- **Caching.** Exchanged tokens are cached in an in-memory Caffeine `AsyncCache`
  keyed by `(subject, audience)`, TTL 90s, single-flight, errors not cached.
  This keeps the gateway from calling Keycloak on every request without holding
  credentials longer than necessary.
- **Header replacement.** The exchanged token replaces (not appends) the
  `Authorization` header.
- **Failure handling.** A failed exchange yields 502 and the downstream service
  is never called. A missing/non-JWT principal yields 401 (defensive).
- **Bounded WebClient timeout.** A 5s response timeout prevents a slow Keycloak
  from hanging ingress threads. Circuit-breaking/retry (Resilience4j) is a later
  hardening step.

## Rationale

- **Audience downscoping is the security point.** Forwarding the raw ingress
  token would either fail at the service (`aud` mismatch) or, worse, hand
  services a token scoped for the gateway. Exchange binds the forwarded token to
  exactly the called service.

- **Caching by `(subject, audience)`.** BFFs reuse a user's token across many
  requests; caching the exchange per subject+audience collapses those into one
  Keycloak call per TTL window. The key omits the exact token/scopes — acceptable
  for Phase 1's coarse scopes (documented limitation).

- **Single-flight async cache.** Mirrors the starter's reactive OPA cache:
  concurrent first-requests for a key share one in-flight exchange, preventing
  stampedes on cold cache or just-expired entries.

## Trade-offs Accepted

- **Credential cache in memory.** Exchanged tokens are bearer credentials held
  briefly in memory. Mitigations: short TTL, bounded size, per-instance (not
  shared), never logged. Acceptable for Phase 1.

- **Coarse cache key.** Scope variation within a TTL window isn't reflected.
  Revisit if fine-grained per-request scopes appear.

- **No resilience yet.** A Keycloak outage fails exchanges (502) without retry
  or circuit-breaking. The stack pins Resilience4j; wiring it here is a follow-up.

- **Exchanger lives in the gateway, not the starter.** Only the gateway exchanges
  tokens in Phase 1. When internal-to-internal exchange appears (a service calling
  another service on a user's behalf), promote the core exchanger to the starter
  and keep the gateway-filter wrapper here.

## Test Boundary

`TokenExchangeFilterIT` uses a WireMock Keycloak token endpoint to verify the
gateway's exchange logic (request shape, header replacement, caching, 502 on
failure). The `act` claim is Keycloak's output, proven by
`ActClaimMapperIT` (keycloak-extensions). The full real-Keycloak slice
(custom image → real `act` → backend `aud` + `act` validation) is the
Sub-phase service integration test.

## References

- RFC 8693 (token exchange)
- gitops ADR 0011 (Token Exchange V2), ADR 0013 (ActClaimMapper image)
- starter ADR 0002 (JWT), ADR 0003 (actor chains)
- ADR 0002 (routing)
