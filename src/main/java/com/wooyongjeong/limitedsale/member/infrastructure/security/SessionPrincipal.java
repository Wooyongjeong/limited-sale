package com.wooyongjeong.limitedsale.member.infrastructure.security;

import com.wooyongjeong.limitedsale.member.api.MemberIdentity;
import java.io.Serializable;

public record SessionPrincipal(long id, String email, String nickname, String role) implements Serializable, MemberIdentity {
}
