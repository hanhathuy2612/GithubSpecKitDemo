package com.example.resetpassword.support;

/** In-memory state (throttles) that IntegrationTestBase resets before each test. */
public interface TestResettable {

    void reset();
}
