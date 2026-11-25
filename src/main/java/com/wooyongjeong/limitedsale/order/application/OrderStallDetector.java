package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.ConsumerProgress;
import com.wooyongjeong.limitedsale.order.domain.ProcessingFailure;
import com.wooyongjeong.limitedsale.order.domain.StallStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Finds accepted requests that were published but never finished (3-6 #1, #2, #7) and sends them again. One counts as
 * stalled once its latest send is a minute old and the consumer group has already read past it, so a request that is
 * merely waiting in a long queue is left alone; after five minutes it counts as stalled whatever the offsets say. A
 * stalled request gets a new outbox row, at most three times, and then ends as PROCESSING_FAILED (STALLED). Only the
 * outbox publisher calls this, so one instance at a time does it.
 */
@Component
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
public class OrderStallDetector {
    private static final int LIMIT = 500;

    private final StallStore store;
    private final ConsumerProgress progress;
    private final OrderAcceptanceFailures failures;
    private final AcceptanceProcessingMetrics metrics;
    private final Duration stallAfter;
    private final Duration deadline;
    private final int maxRepublishes;
    private final AtomicInteger stalled = new AtomicInteger();

    public OrderStallDetector(StallStore store, ConsumerProgress progress, OrderAcceptanceFailures failures,
            AcceptanceProcessingMetrics metrics, MeterRegistry registry,
            @Value("${limited-sale.order.stall.after-publish:PT60S}") Duration stallAfter,
            @Value("${limited-sale.order.stall.deadline:PT5M}") Duration deadline,
            @Value("${limited-sale.order.stall.max-republishes:3}") int maxRepublishes) {
        this.store = store;
        this.progress = progress;
        this.failures = failures;
        this.metrics = metrics;
        this.stallAfter = stallAfter;
        this.deadline = deadline;
        this.maxRepublishes = maxRepublishes;
        Gauge.builder("order.acceptance.stalled", stalled, AtomicInteger::get)
            .description("Stalled acceptances found by the last stall check").register(registry);
    }

    /** One check; returns how many stalled acceptances it found (each republished or failed). */
    public int scan() {
        var candidates = store.publishedAndUnfinished(stallAfter, LIMIT);
        if (candidates.isEmpty()) {
            stalled.set(0);
            return 0;
        }
        // Without the offsets only the deadline can tell; a broker that cannot answer must not stop that.
        Map<Integer, Long> committed = progress.committedOffsets().orElse(Map.of());
        int found = 0;
        for (var candidate : candidates) {
            if (!candidate.passedBy(committed) && candidate.sincePublished().compareTo(deadline) < 0) {
                continue;
            }
            found++;
            if (candidate.sends() > maxRepublishes) {
                failures.fail(candidate.acceptanceId(), ProcessingFailure.STALLED);
            } else {
                store.republish(candidate);
                metrics.republished();
            }
        }
        stalled.set(found);
        return found;
    }
}
