package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceFailures;
import com.wooyongjeong.limitedsale.order.domain.ProcessingFailure;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * How a message whose processing failed is retried (3-6 #3 to #5). Spring Boot hands this error handler to the listener
 * container factory; the container re-seeks the partition, so later messages of the same partition wait their turn.
 * <ul>
 * <li>A transient error is retried in place with growing waits until the retry budget (30 s) has passed since the
 * first failure; then the acceptance is stored as PROCESSING_FAILED (TRANSIENT_RETRIES_EXHAUSTED) and the message is
 * acknowledged.
 * <li>Any other error is stored as PROCESSING_FAILED (PROCESSING_ERROR) at once.
 * <li>If storing the failure fails as well, the database is taken to be away: the message is retried at a fixed
 * interval for as long as that lasts and is processed normally once the database answers again.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.consumer.enabled"},
    havingValue = "true")
class OrderAcceptanceConsumerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(OrderAcceptanceConsumerConfiguration.class);

    @Bean
    AcceptanceRetryState acceptanceRetryState() {
        return new AcceptanceRetryState();
    }

    @Bean
    DefaultErrorHandler orderAcceptanceErrorHandler(OrderAcceptanceFailures failures, AcceptanceRetryState state,
            @Value("${limited-sale.order.consumer.retry-budget:PT30S}") Duration budget,
            @Value("${limited-sale.order.consumer.outage-retry-interval:PT5S}") Duration outageInterval) {
        var transientRetry = new WallClockBackOff(Duration.ofMillis(500), Duration.ofSeconds(8), budget,
            System::nanoTime);
        var handler = new DefaultErrorHandler((record, exception) -> recover(failures, state, record, exception),
            transientRetry);
        handler.setBackOffFunction((record, exception) -> {
            if (state.inOutage(record)) {
                return new FixedBackOff(outageInterval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS);
            }
            return TransientFailures.isTransient(exception) ? transientRetry : new FixedBackOff(0, 0);
        });
        // A failed recovery starts a new back-off for the record, which the function above makes the fixed interval.
        handler.setResetStateOnRecoveryFailure(true);
        return handler;
    }

    private static void recover(OrderAcceptanceFailures failures, AcceptanceRetryState state,
            ConsumerRecord<?, ?> record, Exception exception) {
        var message = OrderAcceptanceListener.read(record.value() == null ? null : record.value().toString());
        if (message == null) {
            // The listener acknowledges unreadable messages itself; nothing can be stored for one.
            return;
        }
        ProcessingFailure reason = TransientFailures.isTransient(exception)
            ? ProcessingFailure.TRANSIENT_RETRIES_EXHAUSTED : ProcessingFailure.PROCESSING_ERROR;
        try {
            failures.fail(message.acceptanceId(), reason);
            state.finished(record);
            log.warn("Order acceptance {} failed with {} at {}-{}@{}: {}", message.acceptanceId(), reason,
                record.topic(), record.partition(), record.offset(), rootMessage(exception));
        } catch (RuntimeException notStored) {
            state.failureNotStored(record);
            log.warn("Could not store the failure of order acceptance {}; retrying it while the database is away: {}",
                message.acceptanceId(), rootMessage(notStored));
            throw notStored;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
    }
}
