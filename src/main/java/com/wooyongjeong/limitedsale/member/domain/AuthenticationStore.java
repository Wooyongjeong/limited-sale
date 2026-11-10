package com.wooyongjeong.limitedsale.member.domain;

import java.util.Optional;

public interface AuthenticationStore {
    Optional<AuthenticationRecord> findForAuthentication(String email);
}
