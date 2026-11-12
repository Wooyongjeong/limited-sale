package com.wooyongjeong.limitedsale.member.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.common.web.CommonResponseCode;
import com.wooyongjeong.limitedsale.common.web.FieldErrorResponse;
import com.wooyongjeong.limitedsale.member.application.AuthenticationService;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.member.infrastructure.security.RedisFailureFilter;
import com.wooyongjeong.limitedsale.member.presentation.code.AuthenticationResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SessionController.class)
public class SessionExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(SessionExceptionHandler.class);

    @ExceptionHandler(AuthenticationService.InvalidCredentials.class)
    ResponseEntity<ApiResponse<?>> invalid() {
        return response(AuthenticationResponseCode.INVALID_CREDENTIALS);
    }

    @ExceptionHandler(AuthenticationService.RateLimited.class)
    ResponseEntity<ApiResponse<?>> limited() {
        return response(AuthenticationResponseCode.LOGIN_RATE_LIMITED);
    }

    @ExceptionHandler(MemberService.InputException.class)
    ResponseEntity<ApiResponse<?>> input(MemberService.InputException exception) {
        var errors = exception.errors().stream()
            .map(error -> new FieldErrorResponse(error.field(), error.reason())).toList();
        return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT, errors));
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
        if (RedisFailureFilter.isRedisFailure(exception)
                || exception instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            throw exception;
        }
        log.error("인증 요청 처리 실패", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.of(CommonResponseCode.INTERNAL_ERROR, null));
    }

    private ResponseEntity<ApiResponse<?>> response(AuthenticationResponseCode code) {
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.of(code, null));
    }
}
