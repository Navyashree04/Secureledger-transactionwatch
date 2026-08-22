package com.transactionwatch.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.transactionwatch.alert.Alert;
import com.transactionwatch.alert.AlertRepository;
import com.transactionwatch.cache.RedisAccountActivityService;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Redis is mocked here (not the point of this test — RedisAccountActivityServiceTest
 * covers the real sliding window against Testcontainers Redis) so this test
 * is purely about the Kafka round-trip: publish → listener consumes →
 * RuleEngine runs → a triggered rule ends up as a persisted Alert.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "transactions.created" })
class TransactionEventListenerTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private AlertRepository alertRepository;

    @MockBean
    private RedisAccountActivityService activityService;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void listenerConsumesEventAndRuleEngineTriggersAndPersistsAlert() throws Exception {
        when(activityService.countRecentTransactions(any(), anyLong())).thenReturn(0L);

        String accountId = "acct-" + UUID.randomUUID();
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), accountId,
                new BigDecimal("50000.00"), "INR", "DEBIT", Instant.now(), "secureledger");

        var producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put("key.serializer", StringSerializer.class);
        producerProps.put("value.serializer", StringSerializer.class);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>("transactions.created", accountId, mapper.writeValueAsString(event))).get();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<Alert> alerts = alertRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
            assertThat(alerts).isNotEmpty();
            assertThat(alerts.get(0).getRuleName()).isEqualTo("LARGE_AMOUNT");
        });
    }
}
