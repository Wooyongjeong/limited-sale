package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OutboxHistory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Removes published outbox rows after the retention (7 days by default, stage 4-2). The publisher thread calls
 * {@link #step} between its batches, so one instance does it and a large backlog of old rows is deleted 1,000 at a time
 * without holding up publishing. Acceptances keep their own rows, so reconciliation still works on old sales; what is
 * lost after the retention is the per-send history (republish count, partition and offset).
 */
@Component
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
public class OutboxCleanup {
    static final int BATCH = 1_000;

    private final OutboxHistory history;
    private final Duration retention;
    private final Counter deleted;

    public OutboxCleanup(OutboxHistory history, MeterRegistry registry,
            @Value("${limited-sale.order.outbox.retention:PT168H}") Duration retention) {
        this.history = history;
        this.retention = retention;
        this.deleted = Counter.builder("order.outbox.deleted")
            .description("Published outbox rows deleted after the retention").register(registry);
    }

    /** Deletes one batch; returns whether more old rows may remain. */
    public boolean step() {
        int count = history.deletePublishedOlderThan(retention, BATCH);
        deleted.increment(count);
        return count == BATCH;
    }
}
