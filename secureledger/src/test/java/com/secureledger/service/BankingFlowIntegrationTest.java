package com.secureledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real HTTP + security + persistence stack (H2, test profile)
 * rather than mocking layers out — the properties this system needs to hold
 * (JWT rejection, atomic transfer, idempotency) only mean something when
 * proven through the actual request path.
 *
 * Covers the five required Phase-1 cases:
 *  1. withdraw fails with insufficient balance
 *  2. transfer moves money atomically between two accounts
 *  3. duplicate idempotency key on transfer returns the original result, not a second transfer
 *  4. JWT-protected endpoint rejects requests with no/invalid token
 *  5. deposit increases balance and creates a transaction record
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BankingFlowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private String token;

    @BeforeEach
    void registerUser() {
        String username = "user_" + UUID.randomUUID();
        var body = mapper.createObjectNode().put("username", username).put("password", "correct horse battery");
        ResponseEntity<String> response = rest.postForEntity(url("/auth/register"), new HttpEntity<>(body.toString(), jsonHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        token = readJson(response.getBody(), "token");
    }

    @Test
    void depositIncreasesBalanceAndCreatesTransactionRecord() throws Exception {
        long accountId = createAccount();

        ResponseEntity<String> depositResp = deposit(accountId, "150.00");
        assertThat(depositResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode tx = mapper.readTree(depositResp.getBody());
        assertThat(tx.get("type").asText()).isEqualTo("DEPOSIT");
        assertThat(new BigDecimal(tx.get("balanceAfter").asText())).isEqualByComparingTo("150.00");

        ResponseEntity<String> accountResp = authGet("/accounts/" + accountId);
        BigDecimal balance = new BigDecimal(mapper.readTree(accountResp.getBody()).get("balance").asText());
        assertThat(balance).isEqualByComparingTo("150.00");
    }

    @Test
    void withdrawFailsWithInsufficientBalance() {
        long accountId = createAccount();
        deposit(accountId, "50.00");

        ResponseEntity<String> resp = withdraw(accountId, "100.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody()).contains("insufficient");
    }

    @Test
    void transferMovesMoneyAtomicallyBetweenTwoAccounts() throws Exception {
        long fromId = createAccount();
        long toId = createAccount();
        deposit(fromId, "500.00");

        var transferBody = mapper.createObjectNode()
                .put("fromAccountId", fromId).put("toAccountId", toId).put("amount", "200.00");
        ResponseEntity<String> resp = rest.postForEntity(url("/transfers"),
                new HttpEntity<>(transferBody.toString(), authJsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        BigDecimal fromBalance = new BigDecimal(mapper.readTree(authGet("/accounts/" + fromId).getBody()).get("balance").asText());
        BigDecimal toBalance = new BigDecimal(mapper.readTree(authGet("/accounts/" + toId).getBody()).get("balance").asText());

        assertThat(fromBalance).isEqualByComparingTo("300.00");
        assertThat(toBalance).isEqualByComparingTo("200.00");
    }

    @Test
    void duplicateIdempotencyKeyReturnsOriginalResultNotASecondTransfer() throws Exception {
        long fromId = createAccount();
        long toId = createAccount();
        deposit(fromId, "500.00");

        String idempotencyKey = UUID.randomUUID().toString();
        var transferBody = mapper.createObjectNode()
                .put("fromAccountId", fromId).put("toAccountId", toId).put("amount", "100.00");

        HttpHeaders headers = authJsonHeaders();
        headers.set("Idempotency-Key", idempotencyKey);

        ResponseEntity<String> first = rest.postForEntity(url("/transfers"), new HttpEntity<>(transferBody.toString(), headers), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> replay = rest.postForEntity(url("/transfers"), new HttpEntity<>(transferBody.toString(), headers), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK); // replayed, not created again

        BigDecimal fromBalance = new BigDecimal(mapper.readTree(authGet("/accounts/" + fromId).getBody()).get("balance").asText());
        // Money moved exactly once, not twice.
        assertThat(fromBalance).isEqualByComparingTo("400.00");
    }

    @Test
    void protectedEndpointRejectsRequestsWithNoOrInvalidToken() {
        ResponseEntity<String> noToken = rest.getForEntity(url("/accounts"), String.class);
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders badHeaders = new HttpHeaders();
        badHeaders.set("Authorization", "Bearer not-a-real-token");
        ResponseEntity<String> badToken = rest.exchange(url("/accounts"), HttpMethod.GET, new HttpEntity<>(badHeaders), String.class);
        assertThat(badToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- helpers ----

    private long createAccount() {
        var body = mapper.createObjectNode().put("accountType", "SAVINGS");
        ResponseEntity<String> resp = rest.postForEntity(url("/accounts"), new HttpEntity<>(body.toString(), authJsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.parseLong(readJson(resp.getBody(), "id"));
    }

    private ResponseEntity<String> deposit(long accountId, String amount) {
        var body = mapper.createObjectNode().put("amount", amount);
        return rest.postForEntity(url("/accounts/" + accountId + "/deposit"), new HttpEntity<>(body.toString(), authJsonHeaders()), String.class);
    }

    private ResponseEntity<String> withdraw(long accountId, String amount) {
        var body = mapper.createObjectNode().put("amount", amount);
        return rest.postForEntity(url("/accounts/" + accountId + "/withdraw"), new HttpEntity<>(body.toString(), authJsonHeaders()), String.class);
    }

    private ResponseEntity<String> authGet(String path) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders headers = jsonHeaders();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private String readJson(String body, String field) {
        try {
            return mapper.readTree(body).get(field).asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
