package com.transactionwatch.cache;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the sliding-window count is correct after several writes, including
 * that entries outside the requested window are excluded — this is the
 * property VelocityRule depends on entirely.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RedisAccountActivityServiceTest {

    @Container
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisAccountActivityService activityService;

    @Test
    void countsOnlyTransactionsWithinTheRequestedWindow() {
        String accountId = "acct-" + UUID.randomUUID();
        Instant now = Instant.now();

        // 3 recent (within last 30s), 2 old (5 minutes ago) — outside a 60s window
        activityService.recordActivity(accountId, "t1", now.minusSeconds(5));
        activityService.recordActivity(accountId, "t2", now.minusSeconds(10));
        activityService.recordActivity(accountId, "t3", now.minusSeconds(20));
        activityService.recordActivity(accountId, "t4", now.minusSeconds(300));
        activityService.recordActivity(accountId, "t5", now.minusSeconds(400));

        long countIn60s = activityService.countRecentTransactions(accountId, 60);
        long countIn600s = activityService.countRecentTransactions(accountId, 600);

        assertThat(countIn60s).isEqualTo(3);
        assertThat(countIn600s).isEqualTo(5);
    }

    @Test
    void differentAccountsDoNotShareCounts() {
        String accountA = "acct-" + UUID.randomUUID();
        String accountB = "acct-" + UUID.randomUUID();

        activityService.recordActivity(accountA, "t1", Instant.now());
        activityService.recordActivity(accountA, "t2", Instant.now());
        activityService.recordActivity(accountB, "t3", Instant.now());

        assertThat(activityService.countRecentTransactions(accountA, 60)).isEqualTo(2);
        assertThat(activityService.countRecentTransactions(accountB, 60)).isEqualTo(1);
    }
}
