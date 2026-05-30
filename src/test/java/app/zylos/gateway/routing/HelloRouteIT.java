package app.zylos.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Route-level integration tests for the {@code hello-service} route.
 *
 * <p>Runs a live reactive server (random port) with two WireMock backends:
 * one standing in for Keycloak (OIDC discovery + JWKS) so the real
 * {@code ReactiveJwtDecoder} validates tokens, and one standing in for the
 * internal hello service so the gateway has something real to proxy to.
 *
 * <p>Tokens are minted with {@link JwtTestSupport} and signed with a key whose
 * public half is served in the JWKS — so the full ingress security path runs
 * for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class HelloRouteIT {

    private static final String REALM_PATH = "/realms/zylos";

    private static final WireMockServer KEYCLOAK = new WireMockServer(options().dynamicPort());
    private static final WireMockServer BACKEND = new WireMockServer(options().dynamicPort());
    private static final JwtTestSupport JWT = new JwtTestSupport();

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

        BACKEND.stubFor(get(urlPathEqualTo("/api/v1/hello/me"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"hello from backend\"}")));
    }

    @AfterAll
    static void stopMocks() {
        KEYCLOAK.stop();
        BACKEND.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zylos.security.issuer-uri", HelloRouteIT::issuer);
        registry.add("zylos.security.expected-audience", () -> "zylos-gateway");
        registry.add("zylos.security.actor-chains.enabled", () -> "false");
        // Repoint the hello-service route at the backend mock.
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "hello-service-test-route");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", BACKEND::baseUrl);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/v1/hello/**");
        // Trust all proxies in tests so X-Forwarded-* headers are generated.
        registry.add("spring.cloud.gateway.server.webflux.trusted-proxies", () -> ".*");
    }

    @Test
    void unauthenticatedRequestIsRejected(@Autowired WebTestClient client) {
        client.get().uri("/api/v1/hello/me").exchange().expectStatus().isUnauthorized();

        BACKEND.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/hello/me")));
    }

    @Test
    void authenticatedRequestIsProxiedToBackend(@Autowired WebTestClient client) {
        String token = JWT.mintToken(issuer(), "zylos-gateway", "alice", Duration.ofMinutes(5));

        client.get()
                .uri("/api/v1/hello/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("hello from backend");

        BACKEND.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/hello/me")));
    }

    @Test
    void forwardedHeadersAreAddedForTrustedProxy(
            @org.springframework.beans.factory.annotation.Autowired WebTestClient client) {
        String token = JWT.mintToken(issuer(), "zylos-gateway", "alice", Duration.ofMinutes(5));

        client.get()
                .uri("/api/v1/hello/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();

        BACKEND.verify(
                getRequestedFor(urlPathEqualTo("/api/v1/hello/me")).withHeader("X-Forwarded-Host", matching(".+")));
    }

    @Test
    void tokenWithWrongAudienceIsRejected(@Autowired WebTestClient client) {
        String wrongAud = JWT.mintToken(issuer(), "some-other-service", "alice", Duration.ofMinutes(5));

        client.get()
                .uri("/api/v1/hello/me")
                .header("Authorization", "Bearer " + wrongAud)
                .exchange()
                .expectStatus()
                .isUnauthorized();

        BACKEND.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/hello/me")));
    }

    @Test
    void cookieHeaderIsStrippedBeforeForwarding(
            @org.springframework.beans.factory.annotation.Autowired WebTestClient client) {
        String token = JWT.mintToken(issuer(), "zylos-gateway", "alice", Duration.ofMinutes(5));

        client.get()
                .uri("/api/v1/hello/me")
                .header("Authorization", "Bearer " + token)
                .cookie("SESSION", "should-not-be-forwarded")
                .exchange()
                .expectStatus()
                .isOk();

        BACKEND.verify(getRequestedFor(urlPathEqualTo("/api/v1/hello/me")).withoutHeader("Cookie"));
    }
}
