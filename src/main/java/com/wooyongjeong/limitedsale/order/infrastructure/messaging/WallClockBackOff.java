package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Exponential waits that stop once a budget of wall-clock time has passed since the first failure. Spring's own
 * exponential back-off counts only the waits, so a retry that itself waits on a lock for seconds would stretch the
 * budget far past what 3-6 #5 allows.
 */
final class WallClockBackOff implements BackOff {
    private final Duration first;
    private final Duration longest;
    private final Duration budget;
    private final LongSupplier nanoTime;

    WallClockBackOff(Duration first, Duration longest, Duration budget, LongSupplier nanoTime) {
        this.first = first;
        this.longest = longest;
        this.budget = budget;
        this.nanoTime = nanoTime;
    }

    @Override
    public BackOffExecution start() {
        long started = nanoTime.getAsLong();
        return new BackOffExecution() {
            private long next = first.toMillis();

            @Override
            public long nextBackOff() {
                long elapsed = (nanoTime.getAsLong() - started) / 1_000_000;
                long left = budget.toMillis() - elapsed;
                if (left <= 0) {
                    return STOP;
                }
                long wait = Math.min(next, left);
                next = Math.min(next * 2, longest.toMillis());
                return wait;
            }
        };
    }
}
