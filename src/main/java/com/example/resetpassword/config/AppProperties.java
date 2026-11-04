package com.example.resetpassword.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings bound from {@code app.*}.
 *
 * @param baseUrl   base URL used to build reset links (APP_BASE_URL)
 * @param mailFrom  sender address for outgoing email (APP_MAIL_FROM)
 * @param mailAsync send email on the bounded {@code mailExecutor}; tests set false for deterministic assertions
 */
@ConfigurationProperties("app")
public record AppProperties(String baseUrl, String mailFrom, boolean mailAsync) {
}
