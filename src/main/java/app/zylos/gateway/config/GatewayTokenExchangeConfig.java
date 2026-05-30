package app.zylos.gateway.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import app.zylos.gateway.exchange.TokenExchangeProperties;

import reactor.netty.http.client.HttpClient;

/**
 * Wiring for gateway-side token exchange.
 *
 * <p>Provides a dedicated {@link WebClient} with a bounded response timeout so a
 * slow or unresponsive Keycloak cannot hang ingress request threads
 * indefinitely. (Circuit-breaking / retry via Resilience4j is a later
 * hardening step.)
 */
@Configuration
@EnableConfigurationProperties(TokenExchangeProperties.class)
public class GatewayTokenExchangeConfig {

    @Bean
    public WebClient tokenExchangeWebClient() {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(5));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
