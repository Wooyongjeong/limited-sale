package com.wooyongjeong.limitedsale.sale.api;

public interface SaleQuery {
    SaleDetails get(long saleId);
    SalePage list(int page, int size, Long productId, String status);
}
