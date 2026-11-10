package com.wooyongjeong.limitedsale.member.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.common.web.CommonResponseCode;
import com.wooyongjeong.limitedsale.common.web.ResponseCode;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.member.domain.MemberConflict;
import com.wooyongjeong.limitedsale.member.presentation.code.MemberResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MemberController.class)
public class MemberExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MemberExceptionHandler.class);

    @ExceptionHandler(MemberService.InputException.class)
    ResponseEntity<ApiResponse<?>> input(MemberService.InputException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT, exception.errors()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<?>> malformed() {
        return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT, null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<?>> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(CommonResponseCode.UNSUPPORTED_MEDIA_TYPE.httpStatus())
            .headers(exception.getHeaders()).body(ApiResponse.of(CommonResponseCode.UNSUPPORTED_MEDIA_TYPE, null));
    }

    @ExceptionHandler(MemberConflict.class)
    ResponseEntity<ApiResponse<?>> conflict(MemberConflict exception) {
        ResponseCode code = exception.kind() == MemberConflict.Kind.EMAIL
            ? MemberResponseCode.EMAIL_ALREADY_EXISTS : MemberResponseCode.NICKNAME_ALREADY_EXISTS;
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.of(code, null));
    }

    @ExceptionHandler(MemberService.NicknameUnavailable.class)
    ResponseEntity<ApiResponse<?>> unavailable() {
        return ResponseEntity.status(503)
            .body(ApiResponse.of(MemberResponseCode.NICKNAME_GENERATION_UNAVAILABLE, null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<?>> unexpected(Exception exception) throws Exception {
        if (exception instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            throw exception;
        }
        log.error("회원 요청 처리 실패", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.of(CommonResponseCode.INTERNAL_ERROR, null));
    }
}
