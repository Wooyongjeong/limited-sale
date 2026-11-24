package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

/** {@code time} is the database time the decision was made at. */
public record SaleAdmissionResult(Reason reason, Instant time) {
    public enum Reason {
        ADMITTED,
        SALE_NOT_FOUND,
        SALE_OUTSIDE_PERIOD,
        SALE_STOPPED
    }
}
