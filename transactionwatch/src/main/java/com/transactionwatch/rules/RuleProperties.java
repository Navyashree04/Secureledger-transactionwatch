package com.transactionwatch.rules;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Rule thresholds, externalized to application.yml (transactionwatch.rules.*)
 * rather than hardcoded in the rule classes — so tuning a threshold is a
 * config change, not a code change. See README Phase 11 (optional) for the
 * further step of making these runtime-editable via an admin endpoint /
 * Postgres-backed config table instead of requiring a redeploy.
 */
@ConfigurationProperties(prefix = "transactionwatch.rules")
public record RuleProperties(Velocity velocity, LargeAmount largeAmount) {

    public record Velocity(boolean enabled, int thresholdCount, long windowSeconds) {}

    public record LargeAmount(boolean enabled, BigDecimal thresholdAmount) {}
}
