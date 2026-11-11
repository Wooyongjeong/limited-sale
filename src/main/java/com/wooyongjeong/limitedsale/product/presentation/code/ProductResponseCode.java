package com.wooyongjeong.limitedsale.product.presentation.code;

import com.wooyongjeong.limitedsale.common.web.ResponseCode;

public enum ProductResponseCode implements ResponseCode {
    PRODUCT_CREATED(201, "제품을 등록했습니다"),
    PRODUCT_FOUND(200, "제품을 조회했습니다"),
    PRODUCTS_FOUND(200, "제품 목록을 조회했습니다"),
    PRODUCT_NOT_FOUND(404, "제품을 찾을 수 없습니다");

    private final int status;
    private final String message;

    ProductResponseCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int httpStatus() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
