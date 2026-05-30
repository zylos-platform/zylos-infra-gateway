# Changelog

All notable changes to this repository will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this repository adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- First internal-service route (`hello-service`): `Path=/api/v1/hello/**` →
  env-driven service URI, full path forwarded.
- Forwarded-header trust via `trusted-proxies` (default kind pod CIDR), enabling
  X-Forwarded-* / Forwarded for downstream services.
- `RemoveRequestHeader=Cookie` default filter (no cookies forwarded downstream).
- Route-level integration tests (`HelloRouteIT`) against a live server with a
  WireMock backend and WireMock Keycloak; `JwtTestSupport` for minting real
  RS256 tokens.
- ADR 0002 (routing and forwarded headers).

### Notes

- Until the next PR, the gateway forwards the raw ingress token; Future PR adds the
  per-service token exchange that corrects the downstream audience.
