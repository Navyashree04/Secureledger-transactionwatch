package com.secureledger.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the producer side of the SecureLedger → Kafka → TransactionWatch
 * contract: creating a transaction through the real API results in a
 * correctly-shaped TransactionCreatedEvent landing on "transactions.created",
 * keyed by accountId.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "transactions.created" })
class TransactionEventPublisherTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void depositPublishesTransactionCreatedEventKeyedByAccountId() throws Exception {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-group", "true", embeddedKafkaBroker);
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

        try (Consumer<String, String> consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(consumerProps)) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "transactions.created");

            String token = registerUser();
            long accountId = createAccount(token);
            deposit(token, accountId, "250.00");

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, "transactions.created", Duration.ofSeconds(10));

            assertThat(record.key()).isEqualTo(String.valueOf(accountId));

            var payload = mapper.readTree(record.value());
            assertThat(payload.get("accountId").asText()).isEqualTo(String.valueOf(accountId));
            assertThat(new BigDecimal(payload.get("amount").asText())).isEqualByComparingTo("250.00");
            assertThat(payload.get("direction").asText()).isEqualTo("CREDIT");
            assertThat(payload.get("sourceService").asText()).isEqualTo("secureledger");
            assertThat(payload.has("eventId")).isTrue();
            assertThat(payload.has("occurredAt")).isTrue();
        }
    }

    private String registerUser() throws Exception {
        var body = mapper.createObjectNode().put("username", "kafka_" + UUID.randomUUID()).put("password", "correct horse battery staple");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.postForEntity(url("/auth/register"), new HttpEntity<>(body.toString(), headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(resp.getBody()).get("token").asText();
    }

    private long createAccount(String token) throws Exception {
        var body = mapper.createObjectNode().put("accountType", "SAVINGS");
        ResponseEntity<String> resp = rest.postForEntity(url("/accounts"), new HttpEntity<>(body.toString(), authHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(resp.getBody()).get("id").asLong();
    }

    private void deposit(String token, long accountId, String amount) {
        var body = mapper.createObjectNode().put("amount", amount);
        ResponseEntity<String> resp = rest.postForEntity(url("/accounts/" + accountId + "/deposit"), new HttpEntity<>(body.toString(), authHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
