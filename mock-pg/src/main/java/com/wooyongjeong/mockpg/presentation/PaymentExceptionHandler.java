package com.wooyongjeong.mockpg.presentation;

import com.wooyongjeong.mockpg.common.web.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PaymentExceptionHandler {
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> mediaType() {
        return error(PaymentResponseCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception failure) throws Exception {
        if (failure instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            throw failure;
        }
        return error(PaymentResponseCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> error(PaymentResponseCode code) {
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.of(code, null));
    }
}
