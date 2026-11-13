package com.wooyongjeong.mockpg.presentation;

import com.wooyongjeong.mockpg.common.web.ResponseCode;

public enum PaymentResponseCode implements ResponseCode {
    OK(200),
    PROCESSING(202),
    INVALID_INPUT(400),
    UNAUTHORIZED(401),
    NOT_FOUND(404),
    CONFLICT(409),
    UNSUPPORTED_MEDIA_TYPE(415),
    INTERNAL_ERROR(500);

    private final int status;

    PaymentResponseCode(int status) {
        this.status = status;
    }

    public int httpStatus() {
        return status;
    }

    public String code() {
        return name();
    }

    public String message() {
        return name();
    }
}
