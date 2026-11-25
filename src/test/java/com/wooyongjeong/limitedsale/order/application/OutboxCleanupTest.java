package com.wooyongjeong.limitedsale.order.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxCleanupTest {
    @Test
    void aFullBatchMeansMoreMayRemainAndEveryDeletedRowIsCounted() {
        Queue<Integer> answers = new ArrayDeque<>(List.of(1_000, 1_000, 7));
        List<Duration> asked = new ArrayList<>();
        var registry = new SimpleMeterRegistry();
        var cleanup = new OutboxCleanup((age, limit) -> {
            asked.add(age);
            assertThat(limit).isEqualTo(OutboxCleanup.BATCH);
            return answers.remove();
        }, registry, Duration.ofDays(7));

        assertThat(cleanup.step()).isTrue();
        assertThat(cleanup.step()).isTrue();
        assertThat(cleanup.step()).isFalse();

        assertThat(asked).containsOnly(Duration.ofDays(7));
        assertThat(registry.find("order.outbox.deleted").counter().count()).isEqualTo(2_007);
    }
}
