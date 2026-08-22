package com.transactionwatch.rules;

import com.transactionwatch.event.TransactionCreatedEvent;
import org.springframework.stereotype.Component;

/** Triggered when a single transaction exceeds a configured amount threshold. */
@Component
public class LargeAmountRule implements FraudRule {

    private final RuleProperties properties;

    public LargeAmountRule(RuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public String ruleName() {
        return "LARGE_AMOUNT";
    }

    @Override
    public RuleResult evaluate(TransactionCreatedEvent event, RuleContext context) {
        RuleProperties.LargeAmount config = properties.largeAmount();
        if (!config.enabled()) {
            return RuleResult.notTriggered(ruleName());
        }

        // LIMITATION: compares event.amount() directly against a single
        // threshold regardless of event.currency() — no FX normalization.
        // A multi-currency deployment would need to convert to a common
        // base currency before this comparison. Stated limitation, not
        // implemented, per project scope.
        if (event.amount().compareTo(config.thresholdAmount()) > 0) {
            String reason = "Transaction amount %s %s exceeds threshold %s"
                    .formatted(event.amount(), event.currency(), config.thresholdAmount());
            return new RuleResult(true, ruleName(), reason, Severity.MEDIUM);
        }
        return RuleResult.notTriggered(ruleName());
    }
}
