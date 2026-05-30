package app.zylos.gateway.exchange;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import reactor.core.publisher.Mono;

/**
 * Performs RFC 8693 token exchange for the gateway, downscoping a validated
 * ingress token ({@code aud = zylos-gateway}) to a per-service audience
 * ({@code aud = zylos-internal-*}).
 *
 * <p>The gateway authenticates to Keycloak as its confidential client; Keycloak
 * mints a token for the requested audience and the Zylos ActClaimMapper records
 * the gateway as the actor ({@code act = { client_id: zylos-gateway }}).
 *
 * <h2>Caching</h2>
 *
 * <p>Exchanged tokens are cached in an in-memory Caffeine {@link AsyncCache}
 * keyed by {@code (subject, audience)}, expiring after a configured TTL
 * (default 90s, well under the exchanged token's 5-minute lifetime). The
 * async cache provides single-flight: concurrent requests for the same key
 * share one exchange call rather than stampeding Keycloak. Failed exchanges are
 * not cached (Caffeine evicts entries whose future completes exceptionally), so
 * a transient Keycloak error doesn't poison subsequent requests.
 *
 * <p>The cache key intentionally does not include the subject token's exact
 * value or scopes: within the short TTL, a second token for the same subject
 * reuses the first exchanged token. This is acceptable for Phase 1's coarse
 * scopes; a finer key (or no cache) would be needed if per-request scope
 * variation became significant.
 *
 * <p>Token values are never logged.
 */
@Component
public class GatewayTokenExchanger {

    private static final Logger log = LoggerFactory.getLogger(GatewayTokenExchanger.class);

    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";

    private final WebClient webClient;
    private final TokenExchangeProperties props;
    private final AsyncCache<CacheKey, String> cache;
    private final Counter successCounter;
    private final Counter errorCounter;

    public GatewayTokenExchanger(
            WebClient tokenExchangeWebClient, TokenExchangeProperties props, MeterRegistry meterRegistry) {
        this.webClient = tokenExchangeWebClient;
        this.props = props;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(props.cache().ttl())
                .maximumSize(props.cache().maxSize())
                .buildAsync();
        CaffeineCacheMetrics.monitor(meterRegistry, cache.synchronous(), "zylos_token_exchange_cache");
        this.successCounter = meterRegistry.counter("zylos_token_exchange_total", "outcome", "success");
        this.errorCounter = meterRegistry.counter("zylos_token_exchange_total", "outcome", "error");
    }

    /**
     * Exchange the given subject token for one bound to {@code audience}.
     * Returns the exchanged access token (compact JWT).
     */
    public Mono<String> exchange(Jwt subjectToken, String audience) {
        CacheKey key = new CacheKey(subjectToken.getSubject(), audience);
        return Mono.fromCompletionStage(cache.get(
                key,
                (_, _) -> doExchange(subjectToken.getTokenValue(), audience).toFuture()));
    }

    private Mono<String> doExchange(String subjectTokenValue, String audience) {
        return webClient
                .post()
                .uri(props.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", GRANT_TYPE)
                        .with("client_id", props.clientId())
                        .with("client_secret", props.clientSecret())
                        .with("subject_token", subjectTokenValue)
                        .with("subject_token_type", TOKEN_TYPE_ACCESS_TOKEN)
                        .with("audience", audience))
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .map(TokenResponse::accessToken)
                .doOnNext(_ -> successCounter.increment())
                .doOnError(error -> {
                    errorCounter.increment();
                    log.warn("Token exchange failed for audience={}: {}", audience, error.toString());
                });
    }

    private record CacheKey(String subject, String audience) {
        private CacheKey {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(audience, "audience");
        }
    }

    /**
     * Minimal projection of the OAuth2 token response.
     */
    record TokenResponse(@JsonProperty("access_token") String accessToken) {}
}
