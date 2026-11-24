package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OutboxMessage;
import com.wooyongjeong.limitedsale.order.domain.OutboxSender;
import com.wooyongjeong.limitedsale.order.domain.OutboxStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One publishing step: read the oldest unpublished rows, send them all, and record what the broker acknowledged.
 * Only the rows before the first failure are marked published. Rows after it are sent again by the next step,
 * whether or not they got through, so a failure never lets a later row overtake an earlier one for good. The price is
 * a duplicate delivery of the later rows that did get through, which the consumer's idempotency absorbs.
 */
@Component
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
public class OutboxBatch {
    public static final int SIZE = 100;
    /** Slightly above the producer's delivery timeout, so a send that never completes cannot hold the loop. */
    private static final Duration RESULT_WAIT = Duration.ofSeconds(15);

    /**
     * @param read rows read this step
     * @param published rows marked published
     * @param failed rows whose send failed
     */
    public record Result(int read, int published, int failed) {
        public boolean full() {
            return read >= SIZE;
        }

        public boolean empty() {
            return read == 0;
        }

        /** True when the step ended on a failed row, so the rest of the batch waits for the next step. */
        public boolean failedAny() {
            return failed > 0;
        }
    }

    private final OutboxStore store;
    private final OutboxSender sender;
    private final Counter success;
    private final Counter failure;

    public OutboxBatch(OutboxStore store, OutboxSender sender, MeterRegistry registry) {
        this.store = store;
        this.sender = sender;
        this.success = Counter.builder("order.outbox.publish").tag("result", "success")
            .description("Outbox rows by publishing result, per row").register(registry);
        this.failure = Counter.builder("order.outbox.publish").tag("result", "failure")
            .description("Outbox rows by publishing result, per row").register(registry);
    }

    public Result publish() {
        List<OutboxMessage> rows = store.unpublished(SIZE);
        if (rows.isEmpty()) {
            return new Result(0, 0, 0);
        }
        List<CompletableFuture<Void>> sent = new ArrayList<>(rows.size());
        for (OutboxMessage row : rows) {
            CompletableFuture<Void> result = start(row);
            sent.add(result);
            if (result.isCompletedExceptionally()) {
                // A send that has failed already (a broker that is gone blocks each send for the producer's
                // max.block.ms) puts every later row behind a failed one, and those rows are not marked anyway.
                break;
            }
        }
        List<Long> acknowledged = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        boolean reachedFailure = false;
        for (int index = 0; index < sent.size(); index++) {
            boolean ok = await(sent.get(index));
            long id = rows.get(index).id();
            if (!ok) {
                failed.add(id);
                reachedFailure = true;
            } else if (!reachedFailure) {
                acknowledged.add(id);
            }
        }
        // Mark first, so a failure to mark leaves every row unpublished and resends them: at least once, never lost.
        if (!acknowledged.isEmpty()) {
            store.markPublished(acknowledged);
            success.increment(acknowledged.size());
        }
        if (!failed.isEmpty()) {
            store.recordFailures(failed);
            failure.increment(failed.size());
        }
        return new Result(rows.size(), acknowledged.size(), failed.size());
    }

    private CompletableFuture<Void> start(OutboxMessage row) {
        try {
            return sender.send(row);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static boolean await(CompletableFuture<Void> result) {
        try {
            result.get(RESULT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the broker", interrupted);
        }
    }
}
