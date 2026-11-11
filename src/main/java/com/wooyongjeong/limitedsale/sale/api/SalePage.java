package com.wooyongjeong.limitedsale.sale.api;

import java.util.List;

public record SalePage(List<SaleListItem> items, int page, int size, long totalElements, long totalPages) {
    public SalePage {
        items = List.copyOf(items);
    }
}
