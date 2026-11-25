package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;

class WallClockBackOffTest {
    private final AtomicLong now = new AtomicLong();
    private final WallClockBackOff backOff = new WallClockBackOff(Duration.ofMillis(500), Duration.ofSeconds(8),
        Duration.ofSeconds(30), now::get);

    @Test
    void waitsDoubleUpToTheLongestAndStopOnceTheBudgetHasPassed() {
        BackOffExecution execution = backOff.start();
        List<Long> waits = new ArrayList<>();
        long next;
        while ((next = execution.nextBackOff()) != BackOffExecution.STOP) {
            waits.add(next);
            now.addAndGet(Duration.ofMillis(next).toNanos());
        }
        // 0.5 + 1 + 2 + 4 + 8 + 8 = 23.5 s, then only the 6.5 s left of the 30 s budget.
        assertThat(waits).containsExactly(500L, 1000L, 2000L, 4000L, 8000L, 8000L, 6500L);
    }

    @Test
    void timeSpentInTheAttemptsThemselvesCountsAgainstTheBudget() {
        BackOffExecution execution = backOff.start();
        // Each attempt waited 10 s on a lock before it failed.
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(execution.nextBackOff()).isEqualTo(500L);
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(execution.nextBackOff()).isEqualTo(1000L);
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void everyStartHasItsOwnBudget() {
        BackOffExecution first = backOff.start();
        now.addAndGet(Duration.ofSeconds(31).toNanos());
        assertThat(first.nextBackOff()).isEqualTo(BackOffExecution.STOP);
        assertThat(backOff.start().nextBackOff()).isEqualTo(500L);
    }
}
