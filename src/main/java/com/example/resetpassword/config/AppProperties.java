package com.example.resetpassword.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings bound from {@code app.*}.
 *
 * @param baseUrl   base URL used to build reset links (APP_BASE_URL)
 * @param mailFrom  sender address for outgoing email (APP_MAIL_FROM)
 * @param mailAsync send email on the bounded {@code mailExecutor}; tests set false for deterministic assertions
 * @param jwt       access-token signing settings (002 research R2)
 */
@ConfigurationProperties("app")
public record AppProperties(String baseUrl, String mailFrom, boolean mailAsync, Jwt jwt) {

    /**
     * @param issuer     {@code iss} claim
     * @param privateKey PKCS#8 PEM EC P-256 private key (APP_JWT_PRIVATE_KEY); blank = generated outside {@code prod}
     * @param publicKey  X.509 (SPKI) PEM of the matching public key (APP_JWT_PUBLIC_KEY); the JDK cannot derive it
     * @param keyId      {@code kid} header (APP_JWT_KEY_ID)
     */
    public record Jwt(String issuer, String privateKey, String publicKey, String keyId) {
    }
}
