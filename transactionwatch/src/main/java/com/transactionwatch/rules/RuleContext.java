package com.transactionwatch.rules;

import com.transactionwatch.cache.RedisAccountActivityService;

/** Carries whatever shared lookups a rule needs — currently just the Redis activity cache. */
public record RuleContext(RedisAccountActivityService activityService) {}
