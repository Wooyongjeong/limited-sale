package com.wooyongjeong.limitedsale.order.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.common.web.CommonResponseCode;
import com.wooyongjeong.limitedsale.common.web.FieldErrorResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(OrderExceptionHandler.class);

    @ExceptionHandler(InvalidOrderInput.class)
    ResponseEntity<ApiResponse<?>> invalid(InvalidOrderInput exception) {
        return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT,
            List.of(new FieldErrorResponse(exception.field(), exception.getMessage()))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<?>> malformed() {
        return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT, null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<?>> media(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(415).headers(exception.getHeaders())
            .body(ApiResponse.of(CommonResponseCode.UNSUPPORTED_MEDIA_TYPE, null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<?>> unexpected(Exception exception) throws Exception {
        if (exception instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            throw exception;
        }
        log.error("주문 처리 실패", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.of(CommonResponseCode.INTERNAL_ERROR, null));
    }
}
