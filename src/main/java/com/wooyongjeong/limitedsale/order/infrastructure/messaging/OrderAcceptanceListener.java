package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import com.wooyongjeong.limitedsale.order.application.AcceptanceProcessingMetrics;
import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceProcessor;
import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceProcessor.Outcome;
import com.wooyongjeong.limitedsale.order.domain.AcceptanceMessage;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptanceTopic;
import com.wooyongjeong.limitedsale.common.observation.RequestIdFilter;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes accepted order requests. The offset is acknowledged only after the processing transaction has committed,
 * so a consumer that stops in between gets the message again and the locked acceptance row turns it into a no-op.
 * A message that can never be processed (unreadable, or naming no acceptance) is logged, counted and acknowledged;
 * any other failure is thrown to the container's error handler ({@link OrderAcceptanceConsumerConfiguration}).
 */
@Component
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.consumer.enabled"},
    havingValue = "true")
class OrderAcceptanceListener {
    static final String LISTENER_ID = "order-acceptance-consumer";
    private static final Logger log = LoggerFactory.getLogger(OrderAcceptanceListener.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OrderAcceptanceProcessor processor;
    private final AcceptanceProcessingMetrics metrics;
    private final AcceptanceRetryState retries;

    OrderAcceptanceListener(OrderAcceptanceProcessor processor, AcceptanceProcessingMetrics metrics,
            AcceptanceRetryState retries) {
        this.processor = processor;
        this.metrics = metrics;
        this.retries = retries;
    }

    // idIsGroup = false keeps the group of spring.kafka.consumer.group-id; by default the listener id would be the group.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = OrderAcceptanceTopic.NAME)
    void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        // The previous message's request id stays until here, so the error handler could still log with it.
        MDC.remove(RequestIdFilter.MDC_KEY);
        Message message = read(record.value());
        if (message == null) {
            log.warn("Skipping an unreadable order acceptance message at {}-{}@{}", record.topic(), record.partition(),
                record.offset());
            metrics.unreadable();
            acknowledgment.acknowledge();
            return;
        }
        OrderAcceptanceProcessor.Result result;
        try {
            result = processor.process(message.acceptanceId(), message.saleId());
        } catch (RuntimeException failure) {
            // The error handler decides: retry, store the failure, or wait for the database.
            metrics.attemptFailed();
            throw failure;
        }
        Outcome outcome = result.outcome();
        if (outcome == Outcome.SKIPPED) {
            log.warn("Skipping order acceptance message {} for sale {} at {}-{}@{}: no such acceptance for that sale",
                message.acceptanceId(), message.saleId(), record.topic(), record.partition(), record.offset());
        }
        metrics.record(outcome);
        metrics.completed(outcome, result.sinceAcceptance());
        log.debug("Order acceptance {} processed: {}", message.acceptanceId(), outcome);
        acknowledgment.acknowledge();
        retries.finished(record);
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    record Message(UUID acceptanceId, long saleId) {
    }

    /** The message of 3-3, or null when the value is not exactly that: nothing else is ever retried into shape. */
    static Message read(String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(value);
            JsonNode version = root.path("schemaVersion");
            JsonNode acceptanceId = root.path("acceptanceId");
            JsonNode saleId = root.path("saleId");
            if (!version.isIntegralNumber() || version.asLong() != AcceptanceMessage.SCHEMA_VERSION
                    || !acceptanceId.isString() || !saleId.isIntegralNumber() || !saleId.canConvertToLong()) {
                return null;
            }
            return new Message(UUID.fromString(acceptanceId.asString()), saleId.asLong());
        } catch (JacksonException | IllegalArgumentException unreadable) {
            return null;
        }
    }
}
