package com.example.resetpassword.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.resetpassword.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 002 T013: Spring Security must not change 001 error semantics; unknown routes are denied. */
class SecurityRoutingTest extends IntegrationTestBase {

    @Test
    void wrongMethodOn001RouteStays405() throws Exception {
        mockMvc.perform(put("/api/password-reset/requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownPathIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }
}
