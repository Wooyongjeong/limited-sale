package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderView;
import java.util.List;

public record OrderPage(List<OrderView> items, int page, int size, long totalElements, long totalPages) {
    public OrderPage {
        items = List.copyOf(items);
    }
}
