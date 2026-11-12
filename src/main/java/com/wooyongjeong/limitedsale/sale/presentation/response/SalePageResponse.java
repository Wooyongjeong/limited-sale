package com.wooyongjeong.limitedsale.sale.presentation.response;

import com.wooyongjeong.limitedsale.sale.api.SalePage;
import java.util.List;

public record SalePageResponse(List<SaleListItemResponse> items, int page, int size, long totalElements,
        long totalPages) {
    public SalePageResponse {
        items = List.copyOf(items);
    }

    public static SalePageResponse from(SalePage value) {
        return new SalePageResponse(value.items().stream().map(SaleListItemResponse::from).toList(), value.page(),
            value.size(), value.totalElements(), value.totalPages());
    }
}
