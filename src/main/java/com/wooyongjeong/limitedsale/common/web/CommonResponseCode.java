package com.wooyongjeong.limitedsale.common.web;

public enum CommonResponseCode implements ResponseCode {
    INVALID_INPUT(400, "입력값을 확인하세요"),
    UNSUPPORTED_MEDIA_TYPE(415, "지원하지 않는 요청 본문 형식입니다"),
    INTERNAL_ERROR(500, "서버 오류가 발생했습니다");

    private final int status;
    private final String message;

    CommonResponseCode(int status, String message) {
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
