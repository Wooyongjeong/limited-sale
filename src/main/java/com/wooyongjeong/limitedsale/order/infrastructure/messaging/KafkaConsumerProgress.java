package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import com.wooyongjeong.limitedsale.order.domain.ConsumerProgress;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptanceTopic;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Reads the order consumer group's committed offsets. One admin client is opened on first use and kept, because the
 * stall check asks every few seconds and a new client would log its whole configuration each time; it uses the admin
 * settings of 3-2 (short timeouts, reconnect back-off) and is closed with the context.
 */
@Component
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
class KafkaConsumerProgress implements ConsumerProgress, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerProgress.class);
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final KafkaAdmin admin;
    private final String group;
    private AdminClient client;

    KafkaConsumerProgress(KafkaAdmin admin,
            @Value("${spring.kafka.consumer.group-id:limited-sale-order-consumer}") String group) {
        this.admin = admin;
        this.group = group;
    }

    @Override
    public synchronized Optional<Map<Integer, Long>> committedOffsets() {
        try {
            if (client == null) {
                client = AdminClient.create(admin.getConfigurationProperties());
            }
            var offsets = client.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            Map<Integer, Long> committed = new HashMap<>();
            offsets.forEach((partition, offset) -> {
                if (offset != null && partition.topic().equals(OrderAcceptanceTopic.NAME)) {
                    committed.put(partition.partition(), offset.offset());
                }
            });
            return Optional.of(committed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | KafkaException unavailable) {
            log.debug("Could not read the offsets of consumer group {}: {}", group, unavailable.toString());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void destroy() {
        if (client != null) {
            client.close(Duration.ofSeconds(2));
            client = null;
        }
    }
}
