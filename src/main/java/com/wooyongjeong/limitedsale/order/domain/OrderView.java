package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderView(UUID orderId, long saleId, long productId, String productNameSnapshot,
        int quantity, long unitPrice, long totalAmount, String status, Instant createdAt, Instant updatedAt,
        Instant expiresAt) {
}
