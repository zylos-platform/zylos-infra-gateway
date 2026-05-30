package app.zylos.gateway.exchange;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for gateway-side RFC 8693 token exchange.
 *
 * @param clientId      the gateway's confidential client id (the actor recorded
 *                      in the exchanged token's {@code act} claim by the
 *                      Keycloak ActClaimMapper)
 * @param clientSecret  the gateway client's secret (injected from a Kubernetes
 *                      secret in the cluster)
 * @param tokenEndpoint Keycloak token endpoint; defaults to the issuer's
 *                      standard OIDC token endpoint
 * @param cache         exchanged-token cache settings
 */
@Validated
@ConfigurationProperties("zylos.gateway.token-exchange")
public record TokenExchangeProperties(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String tokenEndpoint,
        @DefaultValue Cache cache) {

    /**
     * @param ttl     how long an exchanged token is cached. Must be shorter than
     *                the exchanged token's lifetime (S2S tokens live 5 min); 90s
     *                default leaves comfortable headroom.
     * @param maxSize maximum cached entries (bounded to cap memory/credential
     *                exposure).
     */
    public record Cache(
            @DefaultValue("90s") Duration ttl,
            @DefaultValue("10000") long maxSize) {}
}
