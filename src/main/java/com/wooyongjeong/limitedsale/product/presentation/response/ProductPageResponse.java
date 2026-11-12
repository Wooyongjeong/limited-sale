package com.wooyongjeong.limitedsale.product.presentation.response;

import com.wooyongjeong.limitedsale.product.api.ProductPage;
import java.util.List;

public record ProductPageResponse(List<ProductListItemResponse> items, int page, int size, long totalElements,
        long totalPages) {
    public ProductPageResponse {
        items = List.copyOf(items);
    }

    public static ProductPageResponse from(ProductPage value) {
        return new ProductPageResponse(value.items().stream().map(ProductListItemResponse::from).toList(), value.page(),
            value.size(), value.totalElements(), value.totalPages());
    }
}
