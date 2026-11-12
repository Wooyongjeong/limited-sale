package com.wooyongjeong.limitedsale.member.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.member.application.AuthenticationService;
import com.wooyongjeong.limitedsale.member.infrastructure.security.SessionPrincipal;
import com.wooyongjeong.limitedsale.member.presentation.code.AuthenticationResponseCode;
import com.wooyongjeong.limitedsale.member.presentation.request.LoginRequest;
import com.wooyongjeong.limitedsale.member.presentation.response.CsrfTokenResponse;
import com.wooyongjeong.limitedsale.member.presentation.response.MemberResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication
public class SessionController {
    private final AuthenticationService service;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;

    public SessionController(AuthenticationService service, HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrf) {
        this.service = service;
        this.contexts = contexts;
        this.csrf = csrf;
    }

    @GetMapping(value = "/api/v1/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<CsrfTokenResponse> csrf(CsrfToken token) {
        return ApiResponse.of(AuthenticationResponseCode.CSRF_TOKEN_ISSUED,
            new CsrfTokenResponse(token.getToken(), token.getHeaderName()));
    }

    @PostMapping(value = "/api/v1/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<MemberResponse> login(@RequestBody LoginRequest input, HttpServletRequest request,
            HttpServletResponse response) {
        var record = service.authenticate(input.email(), input.password(), request.getRemoteAddr());
        var member = record.member();
        request.changeSessionId();
        csrf.saveToken(null, request, response);
        var principal = new SessionPrincipal(record.id(), member.email(), member.nickname(), member.role().name());
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_" + member.role().name())));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ApiResponse.of(AuthenticationResponseCode.LOGIN_SUCCEEDED, MemberResponse.from(member));
    }

    @DeleteMapping(value = "/api/v1/sessions/current", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        csrf.saveToken(null, request, response);
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.of(AuthenticationResponseCode.LOGOUT_SUCCEEDED, null);
    }

    @GetMapping(value = "/api/v1/members/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<MemberResponse> me(Authentication authentication) {
        var principal = (SessionPrincipal) authentication.getPrincipal();
        return ApiResponse.of(AuthenticationResponseCode.MEMBER_RETRIEVED,
            new MemberResponse(principal.email(), principal.nickname(), principal.role()));
    }
}
