package com.wooyongjeong.limitedsale.order.presentation.response;

import com.wooyongjeong.limitedsale.order.domain.OrderAcceptance;
import java.time.Instant;
import java.util.UUID;

public record OrderAcceptanceStatusResponse(UUID acceptanceId, long saleId, int quantity, String status,
        String rejectionReason, String failureReason, UUID orderId, Instant acceptedAt, Instant finishedAt) {
    public static OrderAcceptanceStatusResponse from(OrderAcceptance value) {
        return new OrderAcceptanceStatusResponse(value.acceptanceId(), value.saleId(), value.quantity(),
            value.status().name(), value.rejection() == null ? null : value.rejection().name(), value.failureReason(),
            value.orderId(), value.acceptedAt(), value.finishedAt());
    }
}
