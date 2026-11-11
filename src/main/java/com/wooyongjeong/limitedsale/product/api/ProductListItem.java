package com.wooyongjeong.limitedsale.product.api;

import java.time.Instant;

public record ProductListItem(long productId, String name, long basePrice, Instant createdAt, Instant updatedAt) {
}
