package com.wooyongjeong.limitedsale.member.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.member.presentation.code.AuthenticationResponseCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

@Configuration
@ConditionalOnWebApplication
public class SecurityConfiguration {
    @Bean
    HttpSessionCsrfTokenRepository csrfRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper, HttpSessionCsrfTokenRepository csrf,
            HttpSessionSecurityContextRepository contexts) throws Exception {
        http.securityContext(config -> config.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(config -> config.csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/api/v1/csrf").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/*",
                    "/api/v1/sales", "/api/v1/sales/*").permitAll()
                .requestMatchers("/api/v1/products", "/api/v1/products/**",
                    "/api/v1/sales", "/api/v1/sales/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/members", "/api/v1/sessions").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/sessions/current").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) ->
                    write(mapper, res, AuthenticationResponseCode.AUTHENTICATION_REQUIRED))
                .accessDeniedHandler((req, res, ex) -> write(mapper, res,
                    ex instanceof InvalidCsrfTokenException || ex instanceof MissingCsrfTokenException
                        ? AuthenticationResponseCode.CSRF_INVALID : AuthenticationResponseCode.ACCESS_DENIED)))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable());
        return http.build();
    }

    public static void write(ObjectMapper mapper, HttpServletResponse response, AuthenticationResponseCode code)
            throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.of(code, null));
    }
}
