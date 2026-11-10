package com.wooyongjeong.limitedsale.member.presentation.code;

import com.wooyongjeong.limitedsale.common.web.ResponseCode;

public enum AuthenticationResponseCode implements ResponseCode {
    CSRF_TOKEN_ISSUED(200, "CSRF 토큰을 발급했습니다"),
    LOGIN_SUCCEEDED(200, "로그인했습니다"),
    LOGOUT_SUCCEEDED(200, "로그아웃했습니다"),
    MEMBER_RETRIEVED(200, "회원 정보를 조회했습니다"),
    AUTHENTICATION_REQUIRED(401, "로그인이 필요합니다"),
    INVALID_CREDENTIALS(401, "이메일 또는 비밀번호가 올바르지 않습니다"),
    ACCESS_DENIED(403, "접근 권한이 없습니다"),
    CSRF_INVALID(403, "CSRF 토큰이 유효하지 않습니다"),
    LOGIN_RATE_LIMITED(429, "로그인 시도 횟수를 초과했습니다"),
    AUTHENTICATION_UNAVAILABLE(503, "인증 서비스를 사용할 수 없습니다");

    private final int status;
    private final String message;

    AuthenticationResponseCode(int status, String message) {
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
