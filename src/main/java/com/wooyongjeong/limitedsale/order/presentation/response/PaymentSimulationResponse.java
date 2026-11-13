package com.wooyongjeong.limitedsale.order.presentation.response;

import java.util.UUID;

public record PaymentSimulationResponse(UUID orderId, String scenario, Integer delaySeconds) {
}
