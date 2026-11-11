package com.wooyongjeong.limitedsale.product.api;

import java.time.Instant;

public record ProductDetails(long productId, String name, String description, long basePrice,
        Instant createdAt, Instant updatedAt) {
}
