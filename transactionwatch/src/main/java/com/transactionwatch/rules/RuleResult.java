package com.transactionwatch.rules;

public record RuleResult(boolean triggered, String ruleName, String reason, Severity severity) {
    public static RuleResult notTriggered(String ruleName) {
        return new RuleResult(false, ruleName, null, null);
    }
}
