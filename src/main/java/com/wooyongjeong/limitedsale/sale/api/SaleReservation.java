package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

public interface SaleReservation {
    SaleAllocation allocate(long saleId, long memberId, int quantity);

    /**
     * Like {@link #allocate}, for a request accepted at {@code acceptedAt}: the sale period is judged at that time and
     * everything else (stop, member limit, stock) now. The returned time is still the current database time.
     */
    SaleAllocation allocateAccepted(long saleId, long memberId, int quantity, Instant acceptedAt);
    void finish(long orderId, long saleId, long memberId, int quantity, boolean confirmed, Instant time);
    void attach(long orderId, long saleId, long memberId, int quantity, Instant time);
}
