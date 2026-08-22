package com.secureledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires many concurrent transfer requests at the SAME source account and
 * asserts the final balance is exactly what it should be — no lost updates.
 * This is what proves the PESSIMISTIC_WRITE row lock in
 * AccountRepository.findByIdForUpdate is actually doing its job, rather
 * than relying on @Transactional alone (which does not, by itself,
 * serialize concurrent read-then-write balance updates).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrentTransferTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void concurrentTransfersAgainstSameAccountDoNotCorruptBalance() throws Exception {
        String token = registerUser();
        long fromId = createAccount(token);
        long toId = createAccount(token);

        BigDecimal startingBalance = new BigDecimal("10000.00");
        deposit(token, fromId, startingBalance.toPlainString());

        int threadCount = 25;
        BigDecimal perTransfer = new BigDecimal("10.00");

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Runnable> tasks = IntStream.range(0, threadCount)
                .mapToObj(i -> (Runnable) () -> {
                    try {
                        ready.countDown();
                        go.await();
                        ResponseEntity<String> resp = transfer(token, fromId, toId, perTransfer.toPlainString(), UUID.randomUUID().toString());
                        if (resp.getStatusCode().is2xxSuccessful()) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    }
                })
                .collect(Collectors.toList());

        tasks.forEach(pool::submit);
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(threadCount);

        BigDecimal expectedFrom = startingBalance.subtract(perTransfer.multiply(BigDecimal.valueOf(threadCount)));
        BigDecimal expectedTo = perTransfer.multiply(BigDecimal.valueOf(threadCount));

        BigDecimal actualFrom = accountBalance(token, fromId);
        BigDecimal actualTo = accountBalance(token, toId);

        assertThat(actualFrom).isEqualByComparingTo(expectedFrom);
        assertThat(actualTo).isEqualByComparingTo(expectedTo);
        // Conservation of money: nothing created or destroyed by the race.
        assertThat(actualFrom.add(actualTo)).isEqualByComparingTo(startingBalance);
    }

    // ---- helpers ----

    private String registerUser() throws Exception {
        String username = "concurrent_" + UUID.randomUUID();
        var body = mapper.createObjectNode().put("username", username).put("password", "correct horse battery");
        ResponseEntity<String> resp = rest.postForEntity(url("/auth/register"), new HttpEntity<>(body.toString(), jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(resp.getBody()).get("token").asText();
    }

    private long createAccount(String token) throws Exception {
        var body = mapper.createObjectNode().put("accountType", "SAVINGS");
        ResponseEntity<String> resp = rest.postForEntity(url("/accounts"), new HttpEntity<>(body.toString(), authJsonHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(resp.getBody()).get("id").asLong();
    }

    private void deposit(String token, long accountId, String amount) {
        var body = mapper.createObjectNode().put("amount", amount);
        ResponseEntity<String> resp = rest.postForEntity(url("/accounts/" + accountId + "/deposit"), new HttpEntity<>(body.toString(), authJsonHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> transfer(String token, long fromId, long toId, String amount, String idempotencyKey) {
        var body = mapper.createObjectNode().put("fromAccountId", fromId).put("toAccountId", toId).put("amount", amount);
        HttpHeaders headers = authJsonHeaders(token);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.postForEntity(url("/transfers"), new HttpEntity<>(body.toString(), headers), String.class);
    }

    private BigDecimal accountBalance(String token, long accountId) throws Exception {
        ResponseEntity<String> resp = rest.exchange(url("/accounts/" + accountId), HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
        JsonNode node = mapper.readTree(resp.getBody());
        return new BigDecimal(node.get("balance").asText());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private HttpHeaders authJsonHeaders(String token) {
        HttpHeaders headers = jsonHeaders();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }
}
