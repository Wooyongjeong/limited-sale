package com.wooyongjeong.limitedsale.sale.domain;

public enum AllocationDecision {
    ALLOCATED,
    SALE_OUTSIDE_PERIOD,
    SALE_STOPPED,
    PURCHASE_LIMIT_EXCEEDED,
    SALE_SOLD_OUT
}
