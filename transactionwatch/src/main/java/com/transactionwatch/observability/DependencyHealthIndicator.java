package com.transactionwatch.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Reports Kafka broker reachability and Redis connectivity under
 * /actuator/health — beyond the default "is the app up" check, this tells
 * an operator specifically which downstream dependency (if either) is the
 * reason TransactionWatch can't process events, rather than a single
 * opaque UP/DOWN for the whole service.
 */
@Component
public class DependencyHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;
    private final StringRedisTemplate redisTemplate;

    public DependencyHealthIndicator(KafkaAdmin kafkaAdmin, StringRedisTemplate redisTemplate) {
        this.kafkaAdmin = kafkaAdmin;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Health health() {
        boolean kafkaUp = checkKafka();
        boolean redisUp = checkRedis();

        Health.Builder builder = (kafkaUp && redisUp) ? Health.up() : Health.down();
        return builder
                .withDetail("kafka", kafkaUp ? "UP" : "DOWN")
                .withDetail("redis", redisUp ? "UP" : "DOWN")
                .build();
    }

    private boolean checkKafka() {
        try (var admin = org.apache.kafka.clients.admin.Admin.create(kafkaAdmin.getConfigurationProperties())) {
            admin.describeCluster().nodes().get(3, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean checkRedis() {
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception ex) {
            return false;
        }
    }
}
