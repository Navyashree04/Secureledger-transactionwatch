package com.transactionwatch.rules;

import com.transactionwatch.alert.AlertService;
import com.transactionwatch.cache.RedisAccountActivityService;
import com.transactionwatch.event.TransactionCreatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RuleEngineTest {

    private final RedisAccountActivityService activityService = mock(RedisAccountActivityService.class);
    private final AlertService alertService = mock(AlertService.class);

    private TransactionCreatedEvent event() {
        return new TransactionCreatedEvent("e1", "t1", "acct-1", new BigDecimal("50000.00"), "INR", "DEBIT", Instant.now(), "secureledger");
    }

    @Test
    void collectsOnlyTriggeredRulesAndPersistsThem() {
        FraudRule alwaysTriggers = mock(FraudRule.class);
        when(alwaysTriggers.ruleName()).thenReturn("ALWAYS");
        when(alwaysTriggers.evaluate(any(), any())).thenReturn(new RuleResult(true, "ALWAYS", "always fires", Severity.HIGH));

        FraudRule neverTriggers = mock(FraudRule.class);
        when(neverTriggers.ruleName()).thenReturn("NEVER");
        when(neverTriggers.evaluate(any(), any())).thenReturn(RuleResult.notTriggered("NEVER"));

        RuleEngine engine = new RuleEngine(List.of(alwaysTriggers, neverTriggers), activityService, alertService, new SimpleMeterRegistry());

        List<RuleResult> triggered = engine.evaluate(event());

        assertThat(triggered).hasSize(1);
        assertThat(triggered.get(0).ruleName()).isEqualTo("ALWAYS");
        verify(alertService, times(1)).persistAlerts(any(), argThat(list -> list.size() == 1));
    }

    @Test
    void doesNotPersistWhenNothingTriggers() {
        FraudRule neverTriggers = mock(FraudRule.class);
        when(neverTriggers.ruleName()).thenReturn("NEVER");
        when(neverTriggers.evaluate(any(), any())).thenReturn(RuleResult.notTriggered("NEVER"));

        RuleEngine engine = new RuleEngine(List.of(neverTriggers), activityService, alertService, new SimpleMeterRegistry());

        List<RuleResult> triggered = engine.evaluate(event());

        assertThat(triggered).isEmpty();
        verify(alertService, never()).persistAlerts(any(), any());
    }
}
