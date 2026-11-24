package com.wooyongjeong.limitedsale.sale.api;

/**
 * Non-locking read of the facts that decide whether a request may be accepted for later processing: whether the sale
 * exists, whether now is inside its period and whether it is stopped. Stock and purchase limits are not read here;
 * the later allocation decides them. Must be called inside a transaction; the sale row is read without a lock.
 */
public interface SaleAdmission {
    SaleAdmissionResult check(long saleId);
}
