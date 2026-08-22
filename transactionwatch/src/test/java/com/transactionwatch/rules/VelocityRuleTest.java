package com.transactionwatch.rules;

import com.transactionwatch.cache.RedisAccountActivityService;
import com.transactionwatch.event.TransactionCreatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class VelocityRuleTest {

    private final RedisAccountActivityService activityService = Mockito.mock(RedisAccountActivityService.class);
    private final RuleProperties.Velocity config = new RuleProperties.Velocity(true, 5, 60);
    private final RuleProperties properties = new RuleProperties(config, new RuleProperties.LargeAmount(true, new BigDecimal("10000.00")));
    private final VelocityRule rule = new VelocityRule(properties);
    private final RuleContext context = new RuleContext(activityService);

    private TransactionCreatedEvent event() {
        return new TransactionCreatedEvent("e1", "t1", "acct-1", new BigDecimal("100.00"), "INR", "DEBIT", Instant.now(), "secureledger");
    }

    @Test
    void doesNotTriggerWellUnderThreshold() {
        when(activityService.countRecentTransactions(any(), anyLong())).thenReturn(1L);
        RuleResult result = rule.evaluate(event(), context);
        assertThat(result.triggered()).isFalse();
    }

    @Test
    void doesNotTriggerExactlyAtThreshold() {
        // threshold is ">5", so exactly 5 must NOT trigger
        when(activityService.countRecentTransactions(any(), anyLong())).thenReturn(5L);
        RuleResult result = rule.evaluate(event(), context);
        assertThat(result.triggered()).isFalse();
    }

    @Test
    void triggersOneOverThreshold() {
        when(activityService.countRecentTransactions(any(), anyLong())).thenReturn(6L);
        RuleResult result = rule.evaluate(event(), context);
        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("VELOCITY");
        assertThat(result.severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void disabledRuleNeverTriggers() {
        RuleProperties disabledProps = new RuleProperties(
                new RuleProperties.Velocity(false, 5, 60),
                new RuleProperties.LargeAmount(true, new BigDecimal("10000.00")));
        VelocityRule disabledRule = new VelocityRule(disabledProps);
        when(activityService.countRecentTransactions(any(), anyLong())).thenReturn(999L);

        RuleResult result = disabledRule.evaluate(event(), context);
        assertThat(result.triggered()).isFalse();
    }
}
