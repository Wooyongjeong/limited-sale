package com.wooyongjeong.limitedsale.order.domain;

public enum OrderRejection {
    SALE_NOT_FOUND,
    SALE_OUTSIDE_PERIOD,
    SALE_STOPPED,
    PURCHASE_LIMIT_EXCEEDED,
    SALE_SOLD_OUT
}
