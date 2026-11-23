package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderView;
import java.time.Duration;

/** {@code retryAfter} is set only for {@link Kind#RATE_LIMITED}. */
public record OrderResult(Kind kind, OrderView order, Duration retryAfter) {
    public enum Kind {
        CREATED,
        REPLAYED,
        SALE_NOT_FOUND,
        SALE_OUTSIDE_PERIOD,
        SALE_STOPPED,
        PURCHASE_LIMIT_EXCEEDED,
        SALE_SOLD_OUT,
        IDEMPOTENCY_CONFLICT,
        PAYMENT_SUCCEEDED,
        PAYMENT_PENDING,
        PAYMENT_DECLINED,
        ORDER_EXPIRED,
        ORDER_NOT_FOUND,
        RATE_LIMITED
    }

    public OrderResult(Kind kind, OrderView order) {
        this(kind, order, null);
    }

    public static OrderResult rateLimited(Duration retryAfter) {
        return new OrderResult(Kind.RATE_LIMITED, null, retryAfter);
    }
}
