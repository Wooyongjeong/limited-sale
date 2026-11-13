package com.wooyongjeong.mockpg.domain;

import java.time.Instant;

public final class DelayedPayment {
    private DelayedPayment() {
    }

    public static PaymentStatus resolve(Instant due, Instant now, boolean close) {
        if (!now.isBefore(due)) {
            return PaymentStatus.SUCCEEDED;
        }
        return close ? PaymentStatus.CLOSED : PaymentStatus.PROCESSING;
    }
}
