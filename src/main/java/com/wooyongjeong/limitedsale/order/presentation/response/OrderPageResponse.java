package com.wooyongjeong.limitedsale.order.presentation.response;

import com.wooyongjeong.limitedsale.order.application.OrderPage;
import java.util.List;

public record OrderPageResponse(List<OrderViewResponse> items, int page, int size, long totalElements,
        long totalPages) {
    public OrderPageResponse {
        items = List.copyOf(items);
    }

    public static OrderPageResponse from(OrderPage value) {
        return new OrderPageResponse(value.items().stream().map(OrderViewResponse::from).toList(), value.page(),
            value.size(), value.totalElements(), value.totalPages());
    }
}
