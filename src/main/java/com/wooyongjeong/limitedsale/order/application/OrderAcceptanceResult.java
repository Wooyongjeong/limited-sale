package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderAcceptance;
import java.time.Duration;

/** {@code acceptance} is set for ACCEPTED and REPLAYED; {@code retryAfter} only for RATE_LIMITED. */
public record OrderAcceptanceResult(Kind kind, OrderAcceptance acceptance, Duration retryAfter) {
    public enum Kind {
        ACCEPTED,
        REPLAYED,
        SALE_NOT_FOUND,
        SALE_OUTSIDE_PERIOD,
        SALE_STOPPED,
        PURCHASE_LIMIT_EXCEEDED,
        SALE_SOLD_OUT,
        IDEMPOTENCY_CONFLICT,
        ACCEPTANCE_NOT_FOUND,
        RATE_LIMITED
    }

    public OrderAcceptanceResult(Kind kind, OrderAcceptance acceptance) {
        this(kind, acceptance, null);
    }

    public static OrderAcceptanceResult rateLimited(Duration retryAfter) {
        return new OrderAcceptanceResult(Kind.RATE_LIMITED, null, retryAfter);
    }
}
