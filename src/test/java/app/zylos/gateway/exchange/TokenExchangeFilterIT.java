package app.zylos.gateway.exchange;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import app.zylos.gateway.support.JwtTestSupport;

/**
 * Integration tests for the gateway-side token-exchange filter.
 *
 * <p>Uses a WireMock Keycloak (OIDC discovery + JWKS for ingress validation,
 * plus a token endpoint returning a canned exchanged token) and a WireMock
 * backend. Verifies the gateway's exchange logic end to end within the gateway:
 * the ingress token is replaced by the exchanged token before forwarding, the
 * exchange request is well-formed, results are cached, and failures surface as
 * 502.
 *
 * <p>The {@code act} claim itself is produced by Keycloak's ActClaimMapper
 * (proven in {@code ActClaimMapperIT}); the full real-Keycloak slice is the
 * Sub-phase service test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TokenExchangeFilterIT {

    private static final String REALM_PATH = "/realms/zylos";
    private static final String TOKEN_PATH = REALM_PATH + "/protocol/openid-connect/token";

    private static final WireMockServer KEYCLOAK = new WireMockServer(options().dynamicPort());
    private static final WireMockServer BACKEND = new WireMockServer(options().dynamicPort());
    private static final JwtTestSupport JWT = new JwtTestSupport();

    private static String exchangedToken;

    @Autowired
    private WebTestClient client;

    private static String issuer() {
        return KEYCLOAK.baseUrl() + REALM_PATH;
    }

    @BeforeAll
    static void startMocks() {
        KEYCLOAK.start();
        BACKEND.start();

        KEYCLOAK.stubFor(get(urlEqualTo(REALM_PATH + "/.well-known/openid-configuration"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {
                          "issuer": "%1$s",
                          "jwks_uri": "%1$s/protocol/openid-connect/certs",
                          "token_endpoint": "%1$s/protocol/openid-connect/token",
                          "authorization_endpoint": "%1$s/protocol/openid-connect/auth",
                          "response_types_supported": ["code"],
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }
                        """.formatted(issuer()))));
        KEYCLOAK.stubFor(get(urlEqualTo(REALM_PATH + "/protocol/openid-connect/certs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(JWT.jwksJson())));

        // The exchanged token the gateway will forward downstream.
        exchangedToken = JWT.mintToken(issuer(), "zylos-internal-hello", "alice", Duration.ofMinutes(5));
        KEYCLOAK.stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"" + exchangedToken
                                + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));

        BACKEND.stubFor(get(urlPathEqualTo("/api/v1/hello/me"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    @AfterAll
    static void stopMocks() {
        KEYCLOAK.stop();
        BACKEND.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zylos.security.issuer-uri", TokenExchangeFilterIT::issuer);
        registry.add("zylos.security.jwk-set-uri", () -> issuer() + "/protocol/openid-connect/certs");
        registry.add("zylos.security.expected-audience", () -> "zylos-gateway");
        registry.add("zylos.security.actor-chains.enabled", () -> "false");

        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "hello-service-test-route");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", BACKEND::baseUrl);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/v1/hello/**");
        registry.add(
                "spring.cloud.gateway.server.webflux.routes[0].filters[0]",
                () -> "TokenExchange=zylos-internal-hello,hello-aud");

        registry.add("spring.cloud.gateway.server.webflux.trusted-proxies", () -> ".*");
        registry.add("zylos.gateway.token-exchange.client-id", () -> "zylos-gateway");
        registry.add("zylos.gateway.token-exchange.client-secret", () -> "test-secret");
        registry.add("zylos.gateway.token-exchange.token-endpoint", () -> issuer() + "/protocol/openid-connect/token");
    }

    @BeforeEach
    void resetJournals() {
        KEYCLOAK.resetRequests();
        BACKEND.resetRequests();
    }

    private String ingressToken(String subject) {
        return JWT.mintToken(issuer(), "zylos-gateway", subject, Duration.ofMinutes(5));
    }

    @Test
    void exchangedTokenReplacesIngressTokenBeforeForwarding() {
        client.get()
                .uri("/api/v1/hello/me")
                .header("Authorization", "Bearer " + ingressToken("user-replace"))
                .exchange()
                .expectStatus()
                .isOk();

        BACKEND.verify(getRequestedFor(urlPathEqualTo("/api/v1/hello/me"))
                .withHeader("Authorization", equalTo("Bearer " + exchangedToken)));
    }

    @Test
    void exchangeRequestIsWellFormed() {
        client.get()
                .uri("/api/v1/hello/me")
                .header("Authorization", "Bearer " + ingressToken("user-form"))
                .exchange()
                .expectStatus()
                .isOk();

        KEYCLOAK.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withRequestBody(containing("token-exchange"))
                .withRequestBody(containing("subject_token_type"))
                .withRequestBody(containing("audience=zylos-internal-hello"))
                .withRequestBody(containing("scope=hello-aud"))
                .withRequestBody(containing("client_id=zylos-gateway")));
    }

    @Test
    void secondRequestForSameSubjectUsesCachedExchange() {
        String token = ingressToken("user-cache");

        for (int i = 0; i < 2; i++) {
            client.get()
                    .uri("/api/v1/hello/me")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus()
                    .isOk();
        }

        // Cache hit on the second request → exactly one exchange call.
        KEYCLOAK.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void exchangeFailureSurfacesAsBadGateway() {
        KEYCLOAK.stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid_request\"}")));

        client.get()
                .uri("/api/v1/hello/me")
                .header("Authorization", "Bearer " + ingressToken("user-fail"))
                .exchange()
                .expectStatus()
                .isEqualTo(502);

        BACKEND.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/hello/me")));

        // Restore the success stub for subsequent tests.
        KEYCLOAK.stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"" + exchangedToken
                                + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    }

    @Test
    void unauthenticatedRequestIsNotExchanged() {
        client.get().uri("/api/v1/hello/me").exchange().expectStatus().isUnauthorized();

        KEYCLOAK.verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        BACKEND.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/hello/me")));
    }
}
