package com.wooyongjeong.limitedsale.product.api;

import java.util.List;

public record ProductPage(List<ProductListItem> items, int page, int size, long totalElements, long totalPages) {
    public ProductPage {
        items = List.copyOf(items);
    }
}
