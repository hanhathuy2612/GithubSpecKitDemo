package com.example.resetpassword.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the email sender and clock with test doubles. Uses {@code @Primary} beans with distinct names instead of
 * bean-definition overriding (which Spring Boot forbids by default). The synchronous mail executor is selected by
 * {@code app.mail-async=false} in application-test.yml.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeansConfig {

    @Bean
    @Primary
    FakeEmailSender fakeEmailSender() {
        return new FakeEmailSender();
    }

    /** MutableClock extends Clock, so @Primary makes it win over the production Clock bean. */
    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(TestFixtures.START);
    }
}
