package com.wooyongjeong.limitedsale.member.presentation.code;

import com.wooyongjeong.limitedsale.common.web.ResponseCode;

public enum MemberResponseCode implements ResponseCode {
    MEMBER_CREATED(201, "회원가입이 완료되었습니다"),
    EMAIL_ALREADY_EXISTS(409, "이미 사용 중인 이메일입니다"),
    NICKNAME_ALREADY_EXISTS(409, "이미 사용 중인 닉네임입니다"),
    NICKNAME_GENERATION_UNAVAILABLE(503, "닉네임을 생성할 수 없습니다");

    private final int status;
    private final String message;

    MemberResponseCode(int status, String message) {
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
