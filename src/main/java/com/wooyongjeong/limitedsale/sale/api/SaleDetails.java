package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

public record SaleDetails(long saleId, long productId, String name, String description, String productNameSnapshot,
        long unitPrice, Instant startsAt, Instant endsAt, int totalQuantity, int perMemberLimit, String status,
        long version, Instant createdAt, Instant updatedAt) {
}
