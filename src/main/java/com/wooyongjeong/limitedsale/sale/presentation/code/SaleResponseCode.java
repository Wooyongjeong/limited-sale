package com.wooyongjeong.limitedsale.sale.presentation.code;

import com.wooyongjeong.limitedsale.common.web.ResponseCode;

public enum SaleResponseCode implements ResponseCode {
    SALE_CREATED(201, "판매 이벤트를 생성했습니다"),
    SALE_UPDATED(200, "판매 이벤트를 수정했습니다"),
    SALE_DELETED(200, "판매 이벤트를 삭제했습니다"),
    SALE_STOPPED(200, "판매 이벤트를 중단했습니다"),
    SALE_RESUMED(200, "판매 이벤트를 재개했습니다"),
    SALE_FOUND(200, "판매 이벤트를 조회했습니다"),
    SALES_FOUND(200, "판매 이벤트 목록을 조회했습니다"),
    SALE_NOT_FOUND(404, "판매 이벤트를 찾을 수 없습니다"),
    PRODUCT_NOT_FOUND(404, "제품을 찾을 수 없습니다"),
    SALE_ALREADY_STARTED(409, "시작 후에는 해당 작업을 수행할 수 없습니다"),
    SALE_ALREADY_ENDED(409, "종료된 이벤트는 중단·재개할 수 없습니다"),
    SALE_VERSION_CONFLICT(409, "이벤트가 변경됐습니다. 다시 조회하세요");

    private final int status;
    private final String message;

    SaleResponseCode(int status, String message) {
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
