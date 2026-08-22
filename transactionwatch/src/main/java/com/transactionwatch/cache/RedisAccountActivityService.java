package com.transactionwatch.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Why Redis, not a Postgres query, for this: every single TransactionCreated
 * event needs a write here (record this transaction's timestamp) and every
 * event needs a read here (how many has this account had recently) — that's
 * a write-and-read on the hot path for 100% of traffic. A sorted set gives
 * O(log N) inserts (ZADD) and O(log N) range counts (ZCOUNT) entirely in
 * memory; running that same access pattern against Postgres would put a
 * relational database — the thing this system is deliberately trying to
 * keep off the critical path — in the loop for every single event, and it
 * would be the first thing to fall over under load.
 *
 * Key layout: one sorted set per account, "activity:{accountId}", where the
 * score is the transaction's epoch-millis timestamp and the member is the
 * transaction id. ZREMRANGEBYSCORE prunes anything older than the retention
 * window on every write, and a matching TTL is set on the key so an account
 * that goes quiet doesn't leave a stale key sitting in memory forever.
 */
@Service
public class RedisAccountActivityService {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long retentionSeconds;

    public RedisAccountActivityService(StringRedisTemplate redisTemplate,
                                        @Value("${transactionwatch.redis.activity-key-prefix}") String keyPrefix,
                                        @Value("${transactionwatch.redis.retention-seconds}") long retentionSeconds) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.retentionSeconds = retentionSeconds;
    }

    public void recordActivity(String accountId, String transactionId, Instant occurredAt) {
        String key = key(accountId);
        long score = occurredAt.toEpochMilli();

        redisTemplate.opsForZSet().add(key, transactionId, score);

        long cutoff = Instant.now().toEpochMilli() - Duration.ofSeconds(retentionSeconds).toMillis();
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoff);

        redisTemplate.expire(key, Duration.ofSeconds(retentionSeconds));
    }

    public long countRecentTransactions(String accountId, long windowSeconds) {
        String key = key(accountId);
        long now = Instant.now().toEpochMilli();
        long from = now - Duration.ofSeconds(windowSeconds).toMillis();
        Long count = redisTemplate.opsForZSet().count(key, from, now);
        return count == null ? 0L : count;
    }

    /** Most recent transaction amount is not tracked yet — amount-aware rules
     * currently read event.amount() directly off the event being evaluated
     * rather than a cached history. Exposed as an extension point for a
     * future rule that needs it (e.g. "average of last N amounts"). */
    public long recentActivityCount(String accountId) {
        Long count = redisTemplate.opsForZSet().zCard(key(accountId));
        return count == null ? 0L : count;
    }

    private String key(String accountId) {
        return keyPrefix + accountId;
    }
}
