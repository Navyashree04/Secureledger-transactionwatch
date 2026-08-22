package com.transactionwatch.alert;

import com.transactionwatch.event.TransactionCreatedEvent;
import com.transactionwatch.exception.AlertNotFoundException;
import com.transactionwatch.rules.RuleResult;
import com.transactionwatch.rules.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DESIGN DECISION — one Alert row per triggered rule, not one row
 * aggregating every rule that fired for a transaction:
 *   A single transaction that trips both VELOCITY and LARGE_AMOUNT is two
 *   materially different findings with different severities and different
 *   review workflows — an analyst dismissing "this account is just doing a
 *   lot of small transfers" (velocity) shouldn't have to also relitigate
 *   "and one of them was unusually large" (large amount) in the same
 *   status field. Separate rows also make GET /api/admin/alerts filterable
 *   by a single ruleName/severity cleanly, which an aggregated row with a
 *   list-of-rules column would not support without a join.
 */
@Service
public class AlertService {

    private final AlertRepository alertRepository;

    public AlertService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Transactional
    public void persistAlerts(TransactionCreatedEvent event, List<RuleResult> triggered) {
        for (RuleResult result : triggered) {
            Alert alert = Alert.builder()
                    .accountId(event.accountId())
                    .transactionId(event.transactionId())
                    .ruleName(result.ruleName())
                    .reason(result.reason())
                    .severity(result.severity())
                    .amount(event.amount())
                    .status(AlertStatus.OPEN)
                    .build();
            alertRepository.save(alert);
        }
    }

    public Page<Alert> search(String accountId, AlertStatus status, Severity severity,
                               Instant from, Instant to, Pageable pageable) {
        return alertRepository.search(accountId, status, severity, from, to, pageable);
    }

    public Alert getById(UUID id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new AlertNotFoundException("No alert with id " + id));
    }

    public List<Alert> getByAccountId(String accountId) {
        return alertRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    @Transactional
    public Alert updateStatus(UUID id, AlertStatus newStatus) {
        Alert alert = getById(id);
        alert.setStatus(newStatus);
        return alertRepository.save(alert);
    }
}
