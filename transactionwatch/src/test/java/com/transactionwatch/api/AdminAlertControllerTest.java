package com.transactionwatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactionwatch.alert.Alert;
import com.transactionwatch.alert.AlertRepository;
import com.transactionwatch.alert.AlertStatus;
import com.transactionwatch.rules.Severity;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminAlertControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AlertRepository alertRepository;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unauthenticatedRequestIsRejected() {
        ResponseEntity<String> resp = rest.getForEntity(url("/api/admin/alerts"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void nonAdminRoleIsForbidden() {
        String token = token("someone", "CUSTOMER");
        ResponseEntity<String> resp = rest.exchange(url("/api/admin/alerts"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanListAlerts() {
        Alert saved = alertRepository.save(seedAlert("acct-list-1"));
        String token = token("admin", "ADMIN");

        ResponseEntity<String> resp = rest.exchange(url("/api/admin/alerts?accountId=acct-list-1"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains(saved.getId().toString());
    }

    @Test
    void adminCanGetSingleAlert() throws Exception {
        Alert saved = alertRepository.save(seedAlert("acct-get-1"));
        String token = token("admin", "ADMIN");

        ResponseEntity<String> resp = rest.exchange(url("/api/admin/alerts/" + saved.getId()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(resp.getBody()).get("ruleName").asText()).isEqualTo("LARGE_AMOUNT");
    }

    @Test
    void gettingMissingAlertReturns404() {
        String token = token("admin", "ADMIN");
        ResponseEntity<String> resp = rest.exchange(url("/api/admin/alerts/" + UUID.randomUUID()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminCanListAlertsForAnAccount() {
        alertRepository.save(seedAlert("acct-by-account-1"));
        String token = token("admin", "ADMIN");

        ResponseEntity<String> resp = rest.exchange(url("/api/admin/accounts/acct-by-account-1/alerts"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("acct-by-account-1");
    }

    @Test
    void adminCanUpdateAlertStatus() throws Exception {
        Alert saved = alertRepository.save(seedAlert("acct-status-1"));
        String token = token("admin", "ADMIN");

        var body = mapper.createObjectNode().put("status", "DISMISSED");
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange(url("/api/admin/alerts/" + saved.getId() + "/status"), HttpMethod.PATCH,
                new HttpEntity<>(body.toString(), headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(resp.getBody()).get("status").asText()).isEqualTo("DISMISSED");
    }

    private Alert seedAlert(String accountId) {
        return Alert.builder()
                .accountId(accountId)
                .transactionId("tx-" + UUID.randomUUID())
                .ruleName("LARGE_AMOUNT")
                .reason("Transaction amount 50000.00 INR exceeds threshold 10000.00")
                .severity(Severity.MEDIUM)
                .amount(new BigDecimal("50000.00"))
                .status(AlertStatus.OPEN)
                .createdAt(Instant.now())
                .build();
    }

    private String token(String username, String role) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000))
                .signWith(key)
                .compact();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
