package app.zylos.gateway.config;

import org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/**
 * Baseline reactive security for the gateway.
 *
 * <p>The gateway is the cluster perimeter and a pure Bearer-token resource
 * server for the {@code zylos-gateway} audience. Ingress tokens (from the web
 * and mobile BFFs) are validated by the security starter's
 * {@code ReactiveJwtDecoder} — signature via JWKS, {@code iss} exact match,
 * {@code aud == zylos-gateway}, {@code exp} + skew. {@link Customizer#withDefaults()}
 * on the JWT spec causes Spring Security to use that decoder bean.
 *
 * <h2>Why actor-chain validation is not wired here</h2>
 *
 * <p>The gateway is the <em>first</em> hop. Ingress tokens carry no {@code act}
 * claim (no delegation has happened yet), so there is no chain to validate.
 * Actor-chain authorization happens at the internal services that receive the
 * gateway's exchanged tokens (which carry {@code act = zylos-gateway}). The
 * starter's {@code actor-chains.enabled} is therefore {@code false} for the
 * gateway (see {@code application.yaml}).
 *
 * <h2>Stateless hardening</h2>
 *
 * <p>CSRF, CORS, form login, HTTP Basic, and the request cache are disabled:
 * the gateway serves stateless, token-authenticated, server-to-server traffic.
 * It has no cookies or sessions (the BFFs own browser-facing concerns), so
 * CSRF is not applicable; browser CORS is handled at the BFF edge.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    @Order(1)
    public SecurityWebFilterChain actuatorSecurityFilterChain(ServerHttpSecurity http) {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }

    @Bean
    @Order(2)
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .build();
    }
}
