package com.wooyongjeong.limitedsale.sale.domain;

/**
 * Writes the sold-out hint that {@link com.wooyongjeong.limitedsale.sale.api.SoldOutGate} reads. Both operations take
 * effect once the current transaction commits and do nothing on rollback, so a reader never sees a decision that
 * MySQL has not committed. A failed write never fails the caller; the hint expires by itself.
 */
public interface SoldOutMarker {
    void markAfterCommit(long saleId);

    void clearAfterCommit(long saleId);
}
