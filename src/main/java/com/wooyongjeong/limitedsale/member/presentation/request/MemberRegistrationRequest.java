package com.wooyongjeong.limitedsale.member.presentation.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = MemberJson.RegistrationDeserializer.class)
public record MemberRegistrationRequest(String email, String password, String nickname) {
    @Override
    public String toString() {
        return "MemberRegistrationRequest[redacted]";
    }
}
