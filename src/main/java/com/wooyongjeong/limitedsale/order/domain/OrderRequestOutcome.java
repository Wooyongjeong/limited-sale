package com.wooyongjeong.limitedsale.order.domain;

public record OrderRequestOutcome(long saleId, int quantity, Long orderId, OrderRejection rejection) {
}
