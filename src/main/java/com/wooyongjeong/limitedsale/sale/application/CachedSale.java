package com.wooyongjeong.limitedsale.sale.application;

import com.wooyongjeong.limitedsale.sale.domain.Sale;
import com.wooyongjeong.limitedsale.sale.domain.SaleTerms;
import java.time.Instant;
import java.util.Objects;

/**
 * Stored values of a sale that are enough to compute its status at any later time. The status itself is never cached
 * because the start and end boundaries must take effect without waiting for the entry to expire. Reserved quantity is
 * left out: it changes with every order and does not affect the status.
 */
public record CachedSale(long saleId, long productId, String name, String description, String productNameSnapshot,
        long unitPrice, Instant startsAt, Instant endsAt, int totalQuantity, int perMemberLimit, int soldQuantity,
        boolean stopped, long version, Instant createdAt, Instant updatedAt) {
    /** Cached text comes from outside the process, so malformed values are rejected here. */
    public CachedSale {
        Objects.requireNonNull(productNameSnapshot, "productNameSnapshot");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (saleId <= 0 || version < 0) {
            throw new IllegalArgumentException("Invalid cached sale identity");
        }
        // SaleTerms validates the terms exactly as when they were stored and rejects null names and times.
        new SaleTerms(productId, name, description, unitPrice, startsAt, endsAt, totalQuantity, perMemberLimit);
        if (soldQuantity < 0 || soldQuantity > totalQuantity) {
            throw new IllegalArgumentException("Invalid cached sold quantity");
        }
    }

    public static CachedSale from(Sale sale) {
        SaleTerms terms = sale.terms();
        return new CachedSale(sale.id(), terms.productId(), terms.name(), terms.description(),
            sale.productNameSnapshot(), terms.unitPrice(), terms.startsAt(), terms.endsAt(), terms.totalQuantity(),
            terms.perMemberLimit(), sale.soldQuantity(), sale.stopped(), sale.version(), sale.createdAt(),
            sale.updatedAt());
    }

    public Sale toSale() {
        SaleTerms terms = new SaleTerms(productId, name, description, unitPrice, startsAt, endsAt, totalQuantity,
            perMemberLimit);
        return new Sale(saleId, terms, productNameSnapshot, 0, soldQuantity, stopped, version, createdAt, updatedAt);
    }
}
