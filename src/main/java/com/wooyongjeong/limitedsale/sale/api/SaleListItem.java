package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

public record SaleListItem(long saleId, long productId, String name, String productNameSnapshot, long unitPrice,
        Instant startsAt, Instant endsAt, int totalQuantity, int perMemberLimit, String status, long version,
        Instant createdAt, Instant updatedAt) {
}
