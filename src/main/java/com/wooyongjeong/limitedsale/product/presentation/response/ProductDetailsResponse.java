package com.wooyongjeong.limitedsale.product.presentation.response;

import com.wooyongjeong.limitedsale.product.api.ProductDetails;
import java.time.Instant;

public record ProductDetailsResponse(long productId, String name, String description, long basePrice,
        Instant createdAt, Instant updatedAt) {
    public static ProductDetailsResponse from(ProductDetails value) {
        return new ProductDetailsResponse(value.productId(), value.name(), value.description(), value.basePrice(),
            value.createdAt(), value.updatedAt());
    }
}
