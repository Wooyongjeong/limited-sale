package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import com.wooyongjeong.limitedsale.order.domain.OutboxMessage;
import com.wooyongjeong.limitedsale.order.domain.OutboxPosition;
import com.wooyongjeong.limitedsale.order.domain.OutboxSender;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Sends a stored outbox row with the application's producer: stored topic, key and payload, and no headers. */
@Component
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
class KafkaOutboxSender implements OutboxSender {
    private final KafkaTemplate<String, String> template;

    KafkaOutboxSender(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    @Override
    public CompletableFuture<OutboxPosition> send(OutboxMessage message) {
        return template.send(message.topic(), message.key(), message.payload()).thenApply(result ->
            new OutboxPosition(result.getRecordMetadata().partition(), result.getRecordMetadata().offset()));
    }
}
