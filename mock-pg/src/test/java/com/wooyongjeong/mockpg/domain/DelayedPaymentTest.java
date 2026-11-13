package com.wooyongjeong.mockpg.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelayedPaymentTest {
    @Test
    void exactDueTimeSucceedsBeforeCloseCanFenceIt() {
        Instant due = Instant.parse("2026-01-01T00:00:10Z");
        assertThat(DelayedPayment.resolve(due, due, true)).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(DelayedPayment.resolve(due, due.minusNanos(1_000), true)).isEqualTo(PaymentStatus.CLOSED);
        assertThat(DelayedPayment.resolve(due, due.minusNanos(1_000), false)).isEqualTo(PaymentStatus.PROCESSING);
    }
}
