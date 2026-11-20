package com.wooyongjeong.limitedsale.order.presentation.code;

import com.wooyongjeong.limitedsale.common.web.ResponseCode;

public enum OrderResponseCode implements ResponseCode {
    ORDER_CREATED(201, "주문을 생성했습니다"),
    ORDER_FOUND(200, "주문을 조회했습니다"),
    ORDERS_FOUND(200, "주문 목록을 조회했습니다"),
    PAYMENT_SUCCEEDED(200, "결제를 완료했습니다"),
    PAYMENT_PENDING(202, "결제 결과를 확인 중입니다"),
    PAYMENT_DECLINED(409, "결제가 거절됐습니다"),
    ORDER_EXPIRED(409, "주문이 만료됐습니다"),
    SALE_NOT_FOUND(404, "판매 이벤트를 찾을 수 없습니다"),
    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다"),
    SALE_OUTSIDE_PERIOD(409, "판매 기간이 아닙니다"),
    SALE_STOPPED(409, "신규 주문이 중단됐습니다"),
    PURCHASE_LIMIT_EXCEEDED(409, "구매 한도를 초과했습니다"),
    SALE_SOLD_OUT(409, "재고가 부족합니다"),
    IDEMPOTENCY_CONFLICT(409, "요청 키가 다른 입력에 사용됐습니다");

    private final int status;
    private final String message;

    OrderResponseCode(int status, String message) {
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
