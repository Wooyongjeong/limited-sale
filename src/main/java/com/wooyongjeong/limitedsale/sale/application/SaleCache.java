package com.wooyongjeong.limitedsale.sale.application;

/**
 * Read-through cache for the sale detail query. It only speeds up display: order allocation and payment never read
 * it, and every failure is reported through {@link Lookup.Failure} or absorbed so the caller can fall back to MySQL.
 */
public interface SaleCache {
    Lookup find(long saleId);

    /** Best effort; a failed store only means the next request reads the database again. */
    void put(CachedSale sale);

    /**
     * Removes the entry once the current transaction commits, so a concurrent reader cannot refill it with the
     * pre-commit value. Nothing is removed if the transaction rolls back. A failed removal never fails the caller.
     */
    void evictAfterCommit(long saleId);

    sealed interface Lookup {
        record Hit(CachedSale sale) implements Lookup {
        }

        record Miss() implements Lookup {
        }

        /** The cache could not be read; callers should not try to write it either. */
        record Failure() implements Lookup {
        }
    }
}
