package com.wooyongjeong.limitedsale.product.domain;

import java.time.Instant;

public record Product(long id, String name, String description, long basePrice, Instant createdAt, Instant updatedAt) {
}
