package com.wooyongjeong.limitedsale.product.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.common.web.CommonResponseCode;
import com.wooyongjeong.limitedsale.common.web.FieldErrorResponse;
import com.wooyongjeong.limitedsale.product.domain.ProductValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProductController.class)
public class ProductExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ProductExceptionHandler.class);

    @ExceptionHandler(ProductValidationException.class)
    ResponseEntity<ApiResponse<?>> invalid(ProductValidationException exception) {
        var errors = exception.errors().stream()
            .map(error -> new FieldErrorResponse(error.field(), error.reason())).toList();
        return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT, errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<?>> malformed() {
        return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT, null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<?>> unsupported(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(415).headers(exception.getHeaders())
            .body(ApiResponse.of(CommonResponseCode.UNSUPPORTED_MEDIA_TYPE, null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<?>> unexpected(Exception exception) throws Exception {
        if (exception instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            throw exception;
        }
        log.error("제품 요청 처리 실패", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.of(CommonResponseCode.INTERNAL_ERROR, null));
    }
}
