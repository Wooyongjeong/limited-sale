package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

public interface SaleReservation {
    SaleAllocation allocate(long saleId, long memberId, int quantity);
    void attach(long orderId, long saleId, long memberId, int quantity, Instant time);
}
