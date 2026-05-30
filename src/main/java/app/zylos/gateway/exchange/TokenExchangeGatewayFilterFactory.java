package app.zylos.gateway.exchange;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Gateway filter that exchanges the validated ingress token for a per-service
 * audience token and replaces the forwarded {@code Authorization} header with it.
 *
 * <p>Reference in route config as {@code TokenExchange} with an {@code audience}
 * argument:
 *
 * <pre>
 * filters:
 *   - name: TokenExchange
 *     args:
 *       audience: zylos-internal-hello
 * </pre>
 *
 * <p>Runs after the security web-filter chain, so {@code exchange.getPrincipal()}
 * yields the validated {@link JwtAuthenticationToken}. On a missing/non-JWT
 * principal the request is rejected 401 (defensive — security should already
 * have enforced authentication). On exchange failure the request is rejected
 * 502 (the upstream identity provider failed), and the downstream service is
 * never called.
 */
@Component
public class TokenExchangeGatewayFilterFactory
        extends AbstractGatewayFilterFactory<TokenExchangeGatewayFilterFactory.Config> {

    private final GatewayTokenExchanger exchanger;

    public TokenExchangeGatewayFilterFactory(GatewayTokenExchanger exchanger) {
        super(Config.class);
        this.exchanger = exchanger;
    }

    private static ServerWebExchange withBearer(ServerWebExchange exchange, String token) {
        return exchange.mutate()
                .request(builder ->
                        builder.headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .build();
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("audience");
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .flatMap(jwt -> exchanger
                        .exchange(jwt, config.getAudience())
                        .onErrorResume(_ -> Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY))))
                .flatMap(exchangedToken -> chain.filter(withBearer(exchange, exchangedToken)))
                .onErrorResume(ResponseStatusException.class, e -> reject(exchange, (HttpStatus) e.getStatusCode()));
    }

    /**
     * Filter configuration: the target audience for the exchanged token.
     */
    @Validated
    public static class Config {

        @NotBlank(message = "The 'audience' argument is strictly required for the TokenExchange filter.")
        private String audience = "";

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }
    }
}
