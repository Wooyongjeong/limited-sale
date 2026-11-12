package com.wooyongjeong.limitedsale.sale.presentation.response;

import com.wooyongjeong.limitedsale.sale.api.SaleDetails;
import java.time.Instant;

public record SaleDetailsResponse(long saleId, long productId, String name, String description,
        String productNameSnapshot,
        long unitPrice, Instant startsAt, Instant endsAt, int totalQuantity, int perMemberLimit, String status,
        long version, Instant createdAt, Instant updatedAt) {
    public static SaleDetailsResponse from(SaleDetails value) {
        return new SaleDetailsResponse(value.saleId(), value.productId(), value.name(), value.description(),
            value.productNameSnapshot(), value.unitPrice(), value.startsAt(), value.endsAt(), value.totalQuantity(),
            value.perMemberLimit(), value.status(), value.version(), value.createdAt(), value.updatedAt());
    }
}
