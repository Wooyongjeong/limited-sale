package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

public record SaleAllocation(Reason reason, long productId, String productName, long unitPrice, Instant time) {
    public enum Reason {
        ALLOCATED,
        SALE_NOT_FOUND,
        SALE_OUTSIDE_PERIOD,
        SALE_STOPPED,
        PURCHASE_LIMIT_EXCEEDED,
        SALE_SOLD_OUT
    }
}
