package app.zylos.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Context-load smoke test for the gateway.
 *
 * <p>Verifies the full reactive context — Spring Cloud Gateway plus the
 * security starter's reactive autoconfiguration plus the baseline
 * {@code SecurityWebFilterChain} — wires without error.
 *
 * <p>A WireMock server stands in for Keycloak's OIDC discovery so the
 * starter's {@code ReactiveJwtDecoder} can resolve the JWKS URI at startup
 * without a real identity provider.
 */
@SpringBootTest
class ZylosInfraGatewayApplicationTests {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlEqualTo("/realms/zylos/.well-known/openid-configuration"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                        {
                                          "issuer": "%1$s/realms/zylos",
                                          "jwks_uri": "%1$s/realms/zylos/protocol/openid-connect/certs",
                                          "authorization_endpoint": "%1$s/realms/zylos/protocol/openid-connect/auth",
                                          "token_endpoint": "%1$s/realms/zylos/protocol/openid-connect/token",
                                          "response_types_supported": ["code"],
                                          "subject_types_supported": ["public"],
                                          "id_token_signing_alg_values_supported": ["RS256"]
                                        }
                                        """.formatted(wireMock.baseUrl()))));
        wireMock.stubFor(get(urlEqualTo("/realms/zylos/protocol/openid-connect/certs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[]}")));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zylos.security.issuer-uri", () -> wireMock.baseUrl() + "/realms/zylos");
        registry.add("zylos.security.expected-audience", () -> "zylos-gateway");
        registry.add("zylos.security.actor-chains.enabled", () -> "false");
    }

    @Test
    void contextLoads() {
        // Success is the context starting with all beans wired.
    }
}
