package app.zylos.gateway.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Test support for minting real RS256 JWTs and serving the matching JWKS.
 *
 * <p>Generates an in-memory RSA key pair at construction. {@link #jwksJson()}
 * returns the public JWK set (to stub Keycloak's certs endpoint), and
 * {@link #mintToken} produces signed tokens the real {@code ReactiveJwtDecoder}
 * accepts — signature verifies against the JWKS, and {@code iss}/{@code aud}/
 * {@code exp} are populated to pass the starter's validators.
 *
 * <p>Using real tokens (rather than mocking the decoder) lets route tests run
 * against a live server and exercise the full ingress security path.
 */
public final class JwtTestSupport {

    private final RSAKey rsaKey;

    public JwtTestSupport() {
        try {
            this.rsaKey = new RSAKeyGenerator(2048).keyID("zylos-test-key-1").generate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate test RSA key", e);
        }
    }

    /**
     * Public JWK set as JSON, for the JWKS (certs) endpoint stub.
     */
    public String jwksJson() {
        return new JWKSet(rsaKey.toPublicJWK()).toString();
    }

    /**
     * Mint a signed RS256 token with the given claims.
     */
    public String mintToken(String issuer, String audience, String subject, Duration validity) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(validity)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(rsaKey.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint test token", e);
        }
    }
}
