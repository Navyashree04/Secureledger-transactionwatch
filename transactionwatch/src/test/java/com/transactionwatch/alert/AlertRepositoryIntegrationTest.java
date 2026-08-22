package com.transactionwatch.alert;

import com.transactionwatch.rules.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs against a real Postgres container (not H2) specifically to prove the
 * Flyway migration (V1__create_alerts_table.sql) applies cleanly and the
 * repository's native filter query works against the real dialect — the
 * H2-backed "test" profile used elsewhere is a fast stand-in, not proof of
 * Postgres compatibility.
 */
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = NONE)
class AlertRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("transactionwatch")
            .withUsername("transactionwatch")
            .withPassword("transactionwatch");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private AlertRepository alertRepository;

    @Test
    void savesAndFindsByAccountId() {
        String accountId = "acct-pg-1";
        Alert alert = Alert.builder()
                .accountId(accountId)
                .transactionId("tx-1")
                .ruleName("LARGE_AMOUNT")
                .reason("Transaction amount 50000.00 INR exceeds threshold 10000.00")
                .severity(Severity.MEDIUM)
                .amount(new BigDecimal("50000.00"))
                .status(AlertStatus.OPEN)
                .build();
        alertRepository.save(alert);

        var found = alertRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRuleName()).isEqualTo("LARGE_AMOUNT");
    }

    @Test
    void searchFiltersByStatusAndSeverity() {
        String accountId = "acct-pg-2";
        alertRepository.save(Alert.builder().accountId(accountId).transactionId("tx-2")
                .ruleName("VELOCITY").reason("r1").severity(Severity.HIGH)
                .amount(new BigDecimal("100.00")).status(AlertStatus.OPEN).build());
        alertRepository.save(Alert.builder().accountId(accountId).transactionId("tx-3")
                .ruleName("LARGE_AMOUNT").reason("r2").severity(Severity.MEDIUM)
                .amount(new BigDecimal("20000.00")).status(AlertStatus.DISMISSED).build());

        var page = alertRepository.search(accountId, AlertStatus.OPEN, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getRuleName()).isEqualTo("VELOCITY");
    }
}
