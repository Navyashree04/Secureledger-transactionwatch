package com.transactionwatch.rules;

import com.transactionwatch.event.TransactionCreatedEvent;
import org.springframework.stereotype.Component;

/** Triggered when an account has more than N transactions within a T-second window. */
@Component
public class VelocityRule implements FraudRule {

    private final RuleProperties properties;

    public VelocityRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public String ruleName() {
        return "VELOCITY";
    }

    @Override
    public RuleResult evaluate(TransactionCreatedEvent event, RuleContext context) {
        RuleProperties.Velocity config = properties.velocity();
        if (!config.enabled()) {
            return RuleResult.notTriggered(ruleName());
        }

        long count = context.activityService().countRecentTransactions(event.accountId(), config.windowSeconds());
        if (count > config.thresholdCount()) {
            String reason = "%d transactions from account %s within %ds (threshold %d)"
                    .formatted(count, event.accountId(), config.windowSeconds(), config.thresholdCount());
            return new RuleResult(true, ruleName(), reason, Severity.HIGH);
        }
        return RuleResult.notTriggered(ruleName());
    }
}
