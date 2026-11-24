package com.wooyongjeong.limitedsale.common.messaging;

import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Creates the {@link NewTopic} beans on a background thread, retrying until the broker answers. Spring Kafka's own
 * admin runs while the context starts and logs a missing broker as an error, which would delay or noise up every
 * start without a broker. The broker's automatic topic creation is off, so a topic that is never created would make
 * later publishing fail until the application restarted.
 */
@Component
@ConditionalOnProperty(name = "limited-sale.kafka.topic-provisioning.enabled", matchIfMissing = true)
class KafkaTopicProvisioner implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(KafkaTopicProvisioner.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(1);
    private static final Duration LONGEST_RETRY = Duration.ofSeconds(30);

    private final KafkaAdmin admin;
    private final List<NewTopic> topics;
    private volatile Thread worker;

    KafkaTopicProvisioner(KafkaAdmin admin, ObjectProvider<NewTopic> topics) {
        this.admin = admin;
        this.topics = topics.orderedStream().toList();
    }

    @Override
    public synchronized void start() {
        if (topics.isEmpty() || worker != null) {
            return;
        }
        worker = Thread.ofPlatform().daemon().name("kafka-topic-provisioner").start(this::provision);
    }

    @Override
    public synchronized void stop() {
        Thread running = worker;
        worker = null;
        if (running != null) {
            running.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return worker != null;
    }

    private void provision() {
        Duration delay = FIRST_RETRY;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                admin.createOrModifyTopics(topics.toArray(NewTopic[]::new));
                log.info("Kafka topics are ready: {}", topics.stream().map(NewTopic::name).toList());
                return;
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                log.warn("Kafka topic creation failed; the application keeps running and retries in {} s: {}",
                        delay.toSeconds(), exception.toString());
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException exception) {
                return;
            }
            delay = delay.multipliedBy(2).compareTo(LONGEST_RETRY) > 0 ? LONGEST_RETRY : delay.multipliedBy(2);
        }
    }
}
