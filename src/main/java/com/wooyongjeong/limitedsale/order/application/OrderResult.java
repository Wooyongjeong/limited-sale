package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderView;

public record OrderResult(Kind kind, OrderView order) {
    public enum Kind {
        CREATED,
        REPLAYED,
        SALE_NOT_FOUND,
        SALE_OUTSIDE_PERIOD,
        SALE_STOPPED,
        PURCHASE_LIMIT_EXCEEDED,
        SALE_SOLD_OUT,
        IDEMPOTENCY_CONFLICT,
        ORDER_NOT_FOUND
    }
}
