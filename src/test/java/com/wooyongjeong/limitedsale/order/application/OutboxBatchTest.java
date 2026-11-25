package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OutboxMessage;
import com.wooyongjeong.limitedsale.order.domain.OutboxPosition;
import com.wooyongjeong.limitedsale.order.domain.OutboxSender;
import com.wooyongjeong.limitedsale.order.domain.OutboxStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The batch rule of 3-4 #2 and #6 with an in-memory store and a sender the test scripts, no broker or database. */
class OutboxBatchTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeStore store = new FakeStore();
    private final FakeSender sender = new FakeSender();
    private final OutboxBatch batch = new OutboxBatch(store, sender, registry);

    @BeforeEach
    void rows() {
        store.add(1, 2, 3, 4, 5);
    }

    private double count(String result) {
        return registry.counter("order.outbox.publish", "result", result).count();
    }

    @Test
    void anEmptyOutboxSendsNothingAndReportsEmpty() {
        FakeStore none = new FakeStore();

        var result = new OutboxBatch(none, sender, registry).publish();

        assertThat(result.empty()).isTrue();
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void everyRowThatTheBrokerAcknowledgesIsMarkedPublishedInIdOrder() {
        var result = batch.publish();

        assertThat(sender.sent).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(store.published).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(store.attempts).isEmpty();
        assertThat(result.failedAny()).isFalse();
        assertThat(count("success")).isEqualTo(5);
        assertThat(count("failure")).isZero();
    }

    @Test
    void aFullBatchIsReadUpTo100RowsAndReportedFull() {
        FakeStore many = new FakeStore();
        for (long id = 1; id <= 250; id++) {
            many.add(id);
        }

        var result = new OutboxBatch(many, sender, registry).publish();

        assertThat(result.full()).isTrue();
        assertThat(result.read()).isEqualTo(100);
        assertThat(many.published).hasSize(100).first().isEqualTo(1L);
    }

    @Test
    void onlyRowsBeforeTheFirstFailureAreMarkedAndTheFailedRowCountsAnAttempt() {
        sender.failing.add(3L);

        var result = batch.publish();

        assertThat(store.published).containsExactly(1L, 2L);
        assertThat(store.attempts).containsExactly(Map.entry(3L, 1));
        assertThat(result.failedAny()).isTrue();
        assertThat(count("success")).isEqualTo(2);
        assertThat(count("failure")).isEqualTo(1);
    }

    @Test
    void rowsAfterAnAlreadyFailedSendAreNotSentInThisBatch() {
        sender.failing.add(2L);

        batch.publish();

        assertThat(sender.sent).containsExactly(1L, 2L);
        assertThat(store.attempts).containsOnlyKeys(2L);
    }

    @Test
    void laterRowsThatSucceedWhileAnEarlierOneFailsLaterAreNotMarkedAndAreSentAgain() {
        // Row 3 fails after rows 4 and 5 have been acknowledged: the failure is only known once results are awaited.
        CompletableFuture<OutboxPosition> slow = new CompletableFuture<>();
        sender.pending.put(3L, slow);
        sender.afterSend = id -> {
            if (id == 5L) {
                slow.completeExceptionally(new IllegalStateException("broker lost row 3"));
            }
        };

        batch.publish();

        assertThat(sender.sent).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(store.published).containsExactly(1L, 2L);
        assertThat(store.attempts).containsExactly(Map.entry(3L, 1));

        sender.pending.clear();
        sender.afterSend = id -> { };
        sender.sent.clear();
        var next = batch.publish();

        // The next attempt starts from the first failed row, so rows 4 and 5 are delivered a second time.
        assertThat(sender.sent).containsExactly(3L, 4L, 5L);
        assertThat(store.published).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(next.failedAny()).isFalse();
    }

    @Test
    void aSenderThatThrowsCountsAsAFailedRow() {
        sender.throwing.add(1L);

        var result = batch.publish();

        assertThat(store.published).isEmpty();
        assertThat(store.attempts).containsExactly(Map.entry(1L, 1));
        assertThat(result.published()).isZero();
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void repeatedFailureOfTheSameRowKeepsRaisingItsAttemptCount() {
        sender.failing.add(1L);

        batch.publish();
        batch.publish();
        batch.publish();

        assertThat(store.attempts).containsExactly(Map.entry(1L, 3));
        assertThat(store.published).isEmpty();
    }

    @Test
    void ifMarkingFailsNothingIsCountedAndEveryRowIsSentAgain() {
        store.failMark = true;

        assertThatThrownBy(batch::publish).isInstanceOf(IllegalStateException.class);
        assertThat(store.published).isEmpty();
        assertThat(count("success")).isZero();

        store.failMark = false;
        sender.sent.clear();
        batch.publish();

        assertThat(sender.sent).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(store.published).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void theBackoffDoublesFromOneSecondToTenSecondsAndStartsOverAfterASuccess() {
        var backoff = new OutboxBackoff(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10));

        List<Long> waits = new ArrayList<>();
        for (int failure = 0; failure < 6; failure++) {
            waits.add(backoff.failure().toSeconds());
        }
        backoff.success();

        assertThat(waits).containsExactly(1L, 2L, 4L, 8L, 10L, 10L);
        assertThat(backoff.failure().toSeconds()).isEqualTo(1);
    }

    private static final class FakeStore implements OutboxStore {
        final List<Long> all = new ArrayList<>();
        final List<Long> published = new ArrayList<>();
        final Map<Long, Integer> attempts = new HashMap<>();
        boolean failMark;

        void add(long... ids) {
            for (long id : ids) {
                all.add(id);
            }
        }

        @Override
        public List<OutboxMessage> unpublished(int limit) {
            return all.stream().filter(id -> !published.contains(id)).limit(limit)
                .map(id -> new OutboxMessage(id, "topic", "key", "payload-" + id, java.time.Duration.ZERO)).toList();
        }

        @Override
        public void markPublished(Map<Long, OutboxPosition> positions) {
            if (failMark) {
                throw new IllegalStateException("the database is gone");
            }
            published.addAll(positions.keySet());
        }

        @Override
        public void recordFailures(Collection<Long> ids) {
            ids.forEach(id -> attempts.merge(id, 1, Integer::sum));
        }
    }

    private static final class FakeSender implements OutboxSender {
        final List<Long> sent = new ArrayList<>();
        final List<Long> failing = new ArrayList<>();
        final List<Long> throwing = new ArrayList<>();
        final Map<Long, CompletableFuture<OutboxPosition>> pending = new HashMap<>();
        Consumer<Long> afterSend = id -> { };

        @Override
        public CompletableFuture<OutboxPosition> send(OutboxMessage message) {
            sent.add(message.id());
            if (throwing.contains(message.id())) {
                throw new IllegalStateException("cannot even start");
            }
            CompletableFuture<OutboxPosition> result = pending.containsKey(message.id()) ? pending.get(message.id())
                : failing.contains(message.id()) ? CompletableFuture.failedFuture(new IllegalStateException("nack"))
                : CompletableFuture.completedFuture(new OutboxPosition(0, message.id()));
            afterSend.accept(message.id());
            return result;
        }
    }
}
