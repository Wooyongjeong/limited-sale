package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * The messages whose failure could not even be stored (3-6 #4): until one of them is processed, it is retried at a
 * fixed interval instead of spending a new retry budget and trying to store the failure again.
 */
final class AcceptanceRetryState {
    private final Set<String> outage = ConcurrentHashMap.newKeySet();

    void failureNotStored(ConsumerRecord<?, ?> record) {
        outage.add(key(record));
    }

    boolean inOutage(ConsumerRecord<?, ?> record) {
        return outage.contains(key(record));
    }

    void finished(ConsumerRecord<?, ?> record) {
        outage.remove(key(record));
    }

    private static String key(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }
}
