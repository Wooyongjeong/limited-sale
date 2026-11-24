package com.wooyongjeong.limitedsale.sale.api;

/**
 * Cheap pre-check that tells order creation a sale is almost certainly sold out, so the request can be turned away
 * without waiting for the sale row lock. A mark is only a hint: MySQL still decides every order that is not turned
 * away here, and the hint never grants anything. A failed lookup reads as "not marked".
 */
public interface SoldOutGate {
    /** Must not be called while a database transaction holds the sale row lock. */
    boolean isMarked(long saleId);
}
