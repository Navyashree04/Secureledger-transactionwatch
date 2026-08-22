package com.secureledger.event;

import com.secureledger.entity.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes a TransactionCreatedEvent for every completed transaction.
 *
 * DESIGN DECISION — publish-after-commit, best-effort (at-least-once at best,
 * not exactly-once), rather than a transactional outbox:
 *
 *   The DB write (the actual money movement) and the Kafka publish are two
 *   different systems that cannot be committed atomically without an outbox
 *   table. This class takes the pragmatic middle path for a portfolio-scoped
 *   system: it registers a TransactionSynchronization that fires ONLY
 *   afterCommit(), so we never publish an event for a transaction that got
 *   rolled back — but the publish itself happens outside the DB transaction,
 *   so a crash between "DB commit" and "Kafka send" would silently drop the
 *   event, and a Kafka broker outage at publish time does the same.
 *
 *   Critically: a Kafka publish failure must NEVER roll back or fail the
 *   transaction API response. The bank transaction already committed and is
 *   real; the customer got their money moved. Failing their HTTP response
 *   because a downstream fraud-detection pipeline couldn't be notified would
 *   be exactly backwards — it would turn an internal messaging hiccup into a
 *   customer-facing outage for something that already succeeded. So publish
 *   failures are logged (at ERROR, with enough fields to find and replay
 *   them) and nothing more.
 *
 *   The real production answer to the gap this leaves is the transactional
 *   outbox pattern: write the event to an "outbox" table in the SAME DB
 *   transaction as the balance change (so it's atomic with the money
 *   movement by construction), then have a separate relay process/CDC
 *   connector (e.g. Debezium) tail that table and publish to Kafka,
 *   retrying indefinitely until it succeeds. That removes the "committed
 *   but never published" window entirely. It's called out here as the
 *   documented next step rather than implemented, per project scope.
 */
@Component
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);
    private static final String TOPIC = "transactions.created";

    private final KafkaTemplate<String, TransactionCreatedEvent> kafkaTemplate;
    private final String defaultCurrency;

    public TransactionEventPublisher(KafkaTemplate<String, TransactionCreatedEvent> kafkaTemplate,
                                      @Value("${ledger.default-currency}") String defaultCurrency) {
        this.kafkaTemplate = kafkaTemplate;
        this.defaultCurrency = defaultCurrency;
    }

    public void publishAfterCommit(Transaction tx) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No active DB transaction (e.g. called from a test harness
            // directly) — publish immediately rather than silently dropping it.
            doPublish(tx);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doPublish(tx);
            }
        });
    }

    private void doPublish(Transaction tx) {
        TransactionCreatedEvent event = TransactionCreatedEvent.from(tx, defaultCurrency);
        kafkaTemplate.send(TOPIC, event.accountId(), event).whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("event_published transactionId={} accountId={} topic={} partition={} offset={}",
                        event.transactionId(), event.accountId(), TOPIC,
                        result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            } else {
                // Deliberately not rethrown — see class-level design decision above.
                log.error("event_publish_failed transactionId={} accountId={} topic={} error={}",
                        event.transactionId(), event.accountId(), TOPIC, ex.getMessage(), ex);
            }
        });
    }
}
