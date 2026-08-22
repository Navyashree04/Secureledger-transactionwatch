package com.transactionwatch.rules;

import com.transactionwatch.cache.RedisAccountActivityService;
import com.transactionwatch.event.TransactionCreatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LargeAmountRuleTest {

    private final RedisAccountActivityService activityService = Mockito.mock(RedisAccountActivityService.class);
    private final RuleProperties properties = new RuleProperties(
            new RuleProperties.Velocity(true, 5, 60),
            new RuleProperties.LargeAmount(true, new BigDecimal("10000.00")));
    private final LargeAmountRule rule = new LargeAmountRule(properties);
    private final RuleContext context = new RuleContext(activityService);

    private TransactionCreatedEvent eventWithAmount(String amount) {
        return new TransactionCreatedEvent("e1", "t1", "acct-1", new BigDecimal(amount), "INR", "DEBIT", Instant.now(), "secureledger");
    }

    @Test
    void doesNotTriggerWellUnderThreshold() {
        assertThat(rule.evaluate(eventWithAmount("500.00"), context).triggered()).isFalse();
    }

    @Test
    void doesNotTriggerExactlyAtThreshold() {
        // threshold is "> 10000.00", so exactly 10000.00 must NOT trigger
        assertThat(rule.evaluate(eventWithAmount("10000.00"), context).triggered()).isFalse();
    }

    @Test
    void triggersJustOverThreshold() {
        RuleResult result = rule.evaluate(eventWithAmount("10000.01"), context);
        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("LARGE_AMOUNT");
        assertThat(result.severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void disabledRuleNeverTriggers() {
        RuleProperties disabledProps = new RuleProperties(
                new RuleProperties.Velocity(true, 5, 60),
                new RuleProperties.LargeAmount(false, new BigDecimal("10000.00")));
        LargeAmountRule disabledRule = new LargeAmountRule(disabledProps);

        assertThat(disabledRule.evaluate(eventWithAmount("999999.00"), context).triggered()).isFalse();
    }
}
