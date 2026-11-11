package com.wooyongjeong.limitedsale.sale.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.common.web.CommonResponseCode;
import com.wooyongjeong.limitedsale.sale.domain.SaleException;
import com.wooyongjeong.limitedsale.sale.presentation.code.SaleResponseCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SaleController.class)
public class SaleExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(SaleExceptionHandler.class);

    public record FieldError(String field, String reason) {
    }

    @ExceptionHandler(SaleException.class)
    ResponseEntity<ApiResponse<?>> business(SaleException exception) {
        if (exception.kind() == SaleException.Kind.INVALID) {
            return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT,
                List.of(new FieldError(exception.field(), exception.getMessage()))));
        }
        SaleResponseCode code = switch (exception.kind()) {
            case NOT_FOUND -> SaleResponseCode.SALE_NOT_FOUND;
            case PRODUCT_NOT_FOUND -> SaleResponseCode.PRODUCT_NOT_FOUND;
            case STARTED -> SaleResponseCode.SALE_ALREADY_STARTED;
            case ENDED -> SaleResponseCode.SALE_ALREADY_ENDED;
            case VERSION_CONFLICT -> SaleResponseCode.SALE_VERSION_CONFLICT;
            case INVALID -> throw new IllegalStateException("Validation handled above");
        };
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.of(code, null));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse<?>> stale() {
        return ResponseEntity.status(409).body(ApiResponse.of(SaleResponseCode.SALE_VERSION_CONFLICT, null));
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
        log.error("판매 이벤트 요청 처리 실패", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.of(CommonResponseCode.INTERNAL_ERROR, null));
    }
}
