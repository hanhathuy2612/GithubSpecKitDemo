package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class ConcurrentConfirmApiTest extends IntegrationTestBase {

    @RepeatedTest(3)
    void twoConcurrentConfirmsWithSameTokenSucceedExactlyOnce() throws Exception {
        String token = seedToken(TestFixtures.ALICE);
        String body = "{\"token\":\"%s\",\"newPassword\":\"brand-new-pass\",\"confirmPassword\":\"brand-new-pass\"}"
                .formatted(token);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/password-reset/confirm")
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                MockHttpServletResponse response = future.get();
                statuses.add(response.getStatus());
                if (response.getStatus() == 400) {
                    assertThat(response.getContentAsString()).contains("/problems/invalid-reset-token");
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
            assertThat(mail.sentTo(TestFixtures.ALICE)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
