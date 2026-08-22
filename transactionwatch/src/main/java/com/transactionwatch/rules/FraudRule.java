package com.transactionwatch.rules;

import com.transactionwatch.event.TransactionCreatedEvent;

public interface FraudRule {
    RuleResult evaluate(TransactionCreatedEvent event, RuleContext context);

    String ruleName();
}
