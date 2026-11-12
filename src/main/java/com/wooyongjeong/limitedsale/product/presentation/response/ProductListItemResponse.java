package com.wooyongjeong.limitedsale.product.presentation.response;

import com.wooyongjeong.limitedsale.product.api.ProductListItem;
import java.time.Instant;

public record ProductListItemResponse(long productId, String name, long basePrice, Instant createdAt,
        Instant updatedAt) {
    public static ProductListItemResponse from(ProductListItem value) {
        return new ProductListItemResponse(value.productId(), value.name(), value.basePrice(), value.createdAt(),
            value.updatedAt());
    }
}
