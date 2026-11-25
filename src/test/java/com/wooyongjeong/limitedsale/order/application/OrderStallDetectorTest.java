package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.ConsumerProgress;
import com.wooyongjeong.limitedsale.order.domain.ProcessingFailure;
import com.wooyongjeong.limitedsale.order.domain.StallStore;
import com.wooyongjeong.limitedsale.order.domain.StalledCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The stall rule of 3-6 #1 and #7 with a scripted store and consumer group, no database or broker. */
class OrderStallDetectorTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<StalledCandidate> candidates = new ArrayList<>();
    private final List<UUID> republished = new ArrayList<>();
    private final List<UUID> failed = new ArrayList<>();
    private Optional<Map<Integer, Long>> committed = Optional.of(Map.of(0, 10L));

    private final StallStore store = new StallStore() {
        @Override
        public List<StalledCandidate> publishedAndUnfinished(Duration publishedAgo, int limit) {
            return candidates;
        }

        @Override
        public void republish(StalledCandidate candidate) {
            republished.add(candidate.acceptanceId());
        }
    };
    private final ConsumerProgress progress = () -> committed;
    private final OrderAcceptanceFailures failures = new OrderAcceptanceFailures(null, null, null) {
        @Override
        public boolean fail(UUID acceptanceId, ProcessingFailure reason) {
            assertThat(reason).isEqualTo(ProcessingFailure.STALLED);
            failed.add(acceptanceId);
            return true;
        }
    };
    private final OrderStallDetector detector = new OrderStallDetector(store, progress, failures,
        new AcceptanceProcessingMetrics(registry), registry, Duration.ofSeconds(60), Duration.ofMinutes(5), 3);

    private StalledCandidate candidate(Integer partition, Long offset, Duration age, int sends) {
        return new StalledCandidate(1, UUID.randomUUID(), "topic", "7", "{}", partition, offset, age, sends);
    }

    private double stalledGauge() {
        return registry.find("order.acceptance.stalled").gauge().value();
    }

    @Test
    void aMessageTheGroupHasReadPastIsRepublished() {
        var passed = candidate(0, 9L, Duration.ofSeconds(61), 1);
        candidates.add(passed);

        assertThat(detector.scan()).isEqualTo(1);
        assertThat(republished).containsExactly(passed.acceptanceId());
        assertThat(stalledGauge()).isEqualTo(1);
    }

    @Test
    void aMessageStillAheadOfTheGroupIsLeftUntilTheDeadline() {
        var waiting = candidate(0, 10L, Duration.ofMinutes(4), 1);
        var otherPartition = candidate(1, 0L, Duration.ofMinutes(4), 1);
        var unknownPosition = candidate(null, null, Duration.ofMinutes(4), 1);
        candidates.addAll(List.of(waiting, otherPartition, unknownPosition));

        assertThat(detector.scan()).isZero();
        assertThat(republished).isEmpty();

        candidates.clear();
        var late = candidate(0, 10L, Duration.ofMinutes(5), 1);
        candidates.add(late);
        assertThat(detector.scan()).isEqualTo(1);
        assertThat(republished).containsExactly(late.acceptanceId());
    }

    @Test
    void withoutOffsetsOnlyTheDeadlineCounts() {
        committed = Optional.empty();
        var passedButUnknown = candidate(0, 0L, Duration.ofSeconds(90), 1);
        var late = candidate(0, 0L, Duration.ofMinutes(6), 1);
        candidates.addAll(List.of(passedButUnknown, late));

        assertThat(detector.scan()).isEqualTo(1);
        assertThat(republished).containsExactly(late.acceptanceId());
    }

    @Test
    void afterThreeRepublishesItFailsAsStalledInsteadOfAFourth() {
        var third = candidate(0, 1L, Duration.ofSeconds(61), 3);
        var exhausted = candidate(0, 1L, Duration.ofSeconds(61), 4);
        candidates.addAll(List.of(third, exhausted));

        assertThat(detector.scan()).isEqualTo(2);
        assertThat(republished).containsExactly(third.acceptanceId());
        assertThat(failed).containsExactly(exhausted.acceptanceId());
    }

    @Test
    void anEmptyCheckResetsTheGauge() {
        candidates.add(candidate(0, 1L, Duration.ofSeconds(61), 1));
        detector.scan();
        candidates.clear();

        assertThat(detector.scan()).isZero();
        assertThat(stalledGauge()).isZero();
    }
}
