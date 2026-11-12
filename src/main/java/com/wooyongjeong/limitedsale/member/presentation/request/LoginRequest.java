package com.wooyongjeong.limitedsale.member.presentation.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = MemberJson.LoginDeserializer.class)
public record LoginRequest(String email, String password) {
    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
