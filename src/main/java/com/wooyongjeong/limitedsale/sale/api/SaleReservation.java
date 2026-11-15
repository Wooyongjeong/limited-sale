package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

public interface SaleReservation {
    SaleAllocation allocate(long saleId, long memberId, int quantity);
    void finish(long orderId, long saleId, long memberId, int quantity, boolean confirmed, Instant time);
    void attach(long orderId, long saleId, long memberId, int quantity, Instant time);
}
