package com.wooyongjeong.limitedsale.common.web;

public record ApiResponse<T>(int httpStatus, String code, T data, String message) {
    public static <T> ApiResponse<T> of(ResponseCode code, T data) {
        return new ApiResponse<>(code.httpStatus(), code.code(), data, code.message());
    }
}
