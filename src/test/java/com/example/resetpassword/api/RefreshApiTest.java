package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.service.auth.IssuedCredentials;
import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class RefreshApiTest extends IntegrationTestBase {

    private MockHttpServletResponse refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}")).andReturn().getResponse();
    }

    private static String refreshTokenOf(MockHttpServletResponse response) throws Exception {
        return JsonPath.read(response.getContentAsString(), "$.refreshToken");
    }

    @Test
    void rotationInvalidatesTheOldToken() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);

        MockHttpServletResponse first = refresh(creds.refreshToken());
        assertThat(first.getStatus()).isEqualTo(200);
        String next = refreshTokenOf(first);
        assertThat(next).isNotEqualTo(creds.refreshToken());

        assertThat(refresh(next).getStatus()).isEqualTo(200);
    }

    @Test
    void reuseOfARotatedTokenRevokesTheWholeSession() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        String next = refreshTokenOf(refresh(creds.refreshToken()));

        assertThat(refresh(creds.refreshToken()).getStatus()).isEqualTo(401);
        assertThat(refresh(next).getStatus()).as("successor revoked too").isEqualTo(401);
    }

    @Test
    void concurrentRefreshesSucceedAtMostOnceThenTheSessionIsRevoked() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return refresh(creds.refreshToken());
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            String winner = null;
            for (Future<MockHttpServletResponse> f : futures) {
                MockHttpServletResponse r = f.get();
                statuses.add(r.getStatus());
                if (r.getStatus() == 200) {
                    winner = refreshTokenOf(r);
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
            assertThat(refresh(winner).getStatus()).as("session revoked by the reuse").isEqualTo(401);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idleSevenDaysIsRejected() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        clock.advance(Duration.ofDays(7));
        assertThat(refresh(creds.refreshToken()).getStatus()).isEqualTo(401);
    }

    @Test
    void thirtyDayCapIsEnforced() throws Exception {
        String token = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD).refreshToken();
        for (int day = 6; day < 30; day += 6) {
            clock.setInstant(TestFixtures.START.plus(Duration.ofDays(day)));
            MockHttpServletResponse r = refresh(token);
            assertThat(r.getStatus()).as("day %s", day).isEqualTo(200);
            token = refreshTokenOf(r);
        }
        clock.setInstant(TestFixtures.START.plus(Duration.ofDays(30)));
        assertThat(refresh(token).getStatus()).isEqualTo(401);
    }

    @Test
    void everyFailureIsTheSameProblem() throws Exception {
        MockHttpServletResponse unknown = refresh(tokenGenerator.generate());
        MockHttpServletResponse malformed = refresh("garbage");
        assertThat(unknown.getStatus()).isEqualTo(401);
        assertThat((String) JsonPath.read(unknown.getContentAsString(), "$.type"))
                .isEqualTo("/problems/invalid-refresh-token");
        assertThat(malformed.getContentAsByteArray()).isEqualTo(unknown.getContentAsByteArray());
    }
}
