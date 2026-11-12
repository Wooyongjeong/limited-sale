package com.wooyongjeong.limitedsale.sale.presentation.response;

import com.wooyongjeong.limitedsale.sale.api.SaleListItem;
import java.time.Instant;

public record SaleListItemResponse(long saleId, long productId, String name, String productNameSnapshot, long unitPrice,
        Instant startsAt, Instant endsAt, int totalQuantity, int perMemberLimit, String status, long version,
        Instant createdAt, Instant updatedAt) {
    public static SaleListItemResponse from(SaleListItem value) {
        return new SaleListItemResponse(value.saleId(), value.productId(), value.name(), value.productNameSnapshot(),
            value.unitPrice(), value.startsAt(), value.endsAt(), value.totalQuantity(), value.perMemberLimit(),
            value.status(), value.version(), value.createdAt(), value.updatedAt());
    }
}
