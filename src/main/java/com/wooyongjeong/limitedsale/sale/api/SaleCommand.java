package com.wooyongjeong.limitedsale.sale.api;

public interface SaleCommand {
    SaleDetails create(SaleCreation input);
    SaleDetails patch(long saleId, SalePatch input);
    void delete(long saleId);
    SaleDetails stop(long saleId);
    SaleDetails resume(long saleId);
}
