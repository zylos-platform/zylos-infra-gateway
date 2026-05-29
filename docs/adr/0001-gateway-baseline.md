# ADR 0001: Gateway Baseline (Reactive Stack, Security Posture)

- **Status:** Accepted
- **Date:** 2026-05-29
- **Relates to:** zylos-infra-security-starter ADR 0001–0006; parent ADR 0004

## Context

Sub-phase introduces the cluster-perimeter API gateway — the first
deployable service in the platform and the first real consumer of the security
starter. It must validate ingress tokens, and route to internal
services while exchanging tokens for per-service audiences.

## Decision

**Spring Cloud Gateway, WebFlux server (`spring-cloud-starter-gateway-server-webflux`).**
The reactive stack is the right fit for a high-throughput, I/O-bound proxy.

**Resource server for `aud = zylos-gateway`.** The gateway accepts only tokens
whose audience is `zylos-gateway`. The security starter's reactive
`ReactiveJwtDecoder` performs validation; `oauth2ResourceServer().jwt()` with
defaults uses that bean.

**No actor-chain validation at the gateway.** The gateway is the first hop;
ingress tokens carry no `act` claim. Chain authorization is the downstream
services' responsibility. `zylos.security.actor-chains.enabled=false`.

**Stateless hardening.** CSRF, CORS, form login, HTTP Basic, and the request
cache are disabled. The gateway is pure Bearer-token, server-to-server; it owns
no cookies or sessions (the BFFs do).

**Containerization via Jib → distroless Java 25.** Consistent with the platform
container standard. Multi-arch (amd64, arm64).

**Inherits `zylos-service-parent`; declares the security starter.** The first
build validates the parent's mandatory-starter enforcer rule (parent ADR 0004).

## Rationale

- **WebFlux over WebMVC gateway.** The proxy workload is I/O-bound;
  non-blocking Netty handles high connection counts with fewer threads. The
  starter's reactive components (`ReactiveJwtDecoder`,
  `ReactiveOpaClient`, `ActorChainReactiveAuthorizationManager`) exist precisely
  for this stack.

- **`aud == zylos-gateway` at ingress.** Enforcing audience at the perimeter
  means a token minted for some other audience cannot be replayed against the
  gateway. The gateway later downscopes (exchanges) to per-service audiences.

## Trade-offs Accepted

- **X-Forwarded-\* disabled by default** (Spring Cloud 2025.1 behavior).
  When routing is added, `trusted-proxies` must be configured if forwarded
  headers are needed for downstream services.

- **CORS disabled.** Correct for server-to-server ingress from the BFFs. If a
  future client calls the gateway directly from a browser, CORS configuration
  must be revisited.

## References

- Spring Cloud Gateway WebFlux
  starter: <https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/starter.html>
- Spring Security 7 reactive resource
  server: <https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html>
- zylos-infra-security-starter ADRs 0002 (JWT), 0005 (MDC/metrics)
- Parent ADR 0004 (mandatory security starter)
