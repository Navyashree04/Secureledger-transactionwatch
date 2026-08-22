package com.transactionwatch.event;

import com.transactionwatch.cache.RedisAccountActivityService;
import com.transactionwatch.rules.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventListener.class);

    private final RedisAccountActivityService activityService;
    private final RuleEngine ruleEngine;

    public TransactionEventListener(RedisAccountActivityService activityService, RuleEngine ruleEngine) {
        this.activityService = activityService;
        this.ruleEngine = ruleEngine;
    }

    @KafkaListener(
            topics = "${transactionwatch.topics.transactions-created}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTransactionCreated(TransactionCreatedEvent event, Acknowledgment ack) {
        log.info("event_received transactionId={} accountId={} amount={} direction={}",
                event.transactionId(), event.accountId(), event.amount(), event.direction());

        activityService.recordActivity(event.accountId(), event.transactionId(), event.occurredAt());
        ruleEngine.evaluate(event);

        // Offset committed only now — after the activity write and rule
        // evaluation (including any alert persistence) have both completed.
        // See KafkaConsumerConfig for why auto-commit is unsafe here.
        ack.acknowledge();
    }
}
