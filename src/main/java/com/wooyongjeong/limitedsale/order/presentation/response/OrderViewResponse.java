package com.wooyongjeong.limitedsale.order.presentation.response;

import com.wooyongjeong.limitedsale.order.domain.OrderView;
import java.time.Instant;
import java.util.UUID;

public record OrderViewResponse(UUID orderId, long saleId, long productId, String productNameSnapshot,
        int quantity, long unitPrice, long totalAmount, String status, Instant createdAt, Instant updatedAt,
        Instant expiresAt) {
    public static OrderViewResponse from(OrderView value) {
        return new OrderViewResponse(value.orderId(), value.saleId(), value.productId(), value.productNameSnapshot(),
            value.quantity(), value.unitPrice(), value.totalAmount(), value.status(), value.createdAt(),
            value.updatedAt(), value.expiresAt());
    }
}
