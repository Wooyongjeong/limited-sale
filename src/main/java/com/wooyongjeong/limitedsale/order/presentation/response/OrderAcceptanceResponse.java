package com.wooyongjeong.limitedsale.order.presentation.response;

import com.wooyongjeong.limitedsale.order.domain.OrderAcceptance;
import java.util.UUID;

public record OrderAcceptanceResponse(UUID acceptanceId, String status, String statusUrl) {
    public static final String STATUS_PATH = "/api/v2/order-requests/";

    public static OrderAcceptanceResponse from(OrderAcceptance value) {
        return new OrderAcceptanceResponse(value.acceptanceId(), value.status().name(),
            STATUS_PATH + value.acceptanceId());
    }
}
