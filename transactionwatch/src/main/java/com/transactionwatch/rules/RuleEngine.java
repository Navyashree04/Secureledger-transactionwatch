package com.transactionwatch.rules;

import com.transactionwatch.alert.AlertService;
import com.transactionwatch.cache.RedisAccountActivityService;
import com.transactionwatch.event.TransactionCreatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<FraudRule> rules;
    private final RedisAccountActivityService activityService;
    private final AlertService alertService;
    private final MeterRegistry meterRegistry;

    // Spring auto-injects every FraudRule bean into this List, in no
    // particular guaranteed order — each rule is independent and order
    // doesn't affect the result, so that's fine.
    public RuleEngine(List<FraudRule> rules,
                       RedisAccountActivityService activityService,
                       AlertService alertService,
                       MeterRegistry meterRegistry) {
        this.rules = rules;
        this.activityService = activityService;
        this.alertService = alertService;
        this.meterRegistry = meterRegistry;
    }

    public List<RuleResult> evaluate(TransactionCreatedEvent event) {
        RuleContext context = new RuleContext(activityService);
        List<RuleResult> triggered = new ArrayList<>();

        for (FraudRule rule : rules) {
            Timer.Sample sample = Timer.start(meterRegistry);
            RuleResult result = rule.evaluate(event, context);
            sample.stop(meterRegistry.timer("transactionwatch.rule.evaluation", "rule", rule.ruleName()));

            log.debug("rule_evaluated rule={} accountId={} transactionId={} triggered={}",
                    rule.ruleName(), event.accountId(), event.transactionId(), result.triggered());

            if (result.triggered()) {
                log.warn("fraud_alert_triggered rule={} accountId={} transactionId={} reason={} severity={}",
                        result.ruleName(), event.accountId(), event.transactionId(), result.reason(), result.severity());
                meterRegistry.counter("transactionwatch.alerts.triggered", "rule", rule.ruleName()).increment();
                triggered.add(result);
            }
        }

        meterRegistry.counter("transactionwatch.events.consumed").increment();

        if (!triggered.isEmpty()) {
            alertService.persistAlerts(event, triggered);
        }

        return triggered;
    }
}
