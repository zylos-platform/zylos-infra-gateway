# Changelog

All notable changes to this repository will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this repository adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Gateway-side RFC 8693 token exchange: a `TokenExchange` route filter that
  downscopes the ingress token's audience to a per-service audience and
  replaces the forwarded `Authorization` header. Produces the single-hop `act`
  claim (gateway as actor) via the Keycloak ActClaimMapper.
- `GatewayTokenExchanger` with an in-memory Caffeine async cache
  (`(subject, audience)` key, 90s TTL, single-flight, errors not cached) and
  `zylos_token_exchange_total` / `zylos_token_exchange_cache` metrics.
- `TokenExchangeProperties` (`zylos.gateway.token-exchange.*`) and a dedicated
  token-exchange `WebClient` with a bounded response timeout.
- `TokenExchangeFilterIT` covering header replacement, exchange request shape,
  caching, 502-on-failure, and unauthenticated short-circuit.
- ADR 0003 (gateway token exchange).

### Changed

- `hello-service` route now applies the `TokenExchange` filter
  (`audience: zylos-internal-hello`).
