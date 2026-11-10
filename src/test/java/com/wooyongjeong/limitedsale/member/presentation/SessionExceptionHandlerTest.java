package com.wooyongjeong.limitedsale.member.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.common.web.CommonResponseCode;
import com.wooyongjeong.limitedsale.member.application.AuthenticationService;
import com.wooyongjeong.limitedsale.member.infrastructure.security.RedisFailureFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionExceptionHandlerTest {
    private final AuthenticationService service = mock(AuthenticationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SessionController(service, null, null))
            .setControllerAdvice(new SessionExceptionHandler())
            .addFilters(new RedisFailureFilter(new ObjectMapper())).build();
    }

    @Test
    void unexpectedFailureUsesCommonResponseWithoutInternalDetails() throws Exception {
        when(service.authenticate(any(), any(), any()))
            .thenThrow(new IllegalStateException("private database failure detail"));
        mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.httpStatus").value(500))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.message").value(CommonResponseCode.INTERNAL_ERROR.message()))
            .andExpect(content().string(not(containsString("private database failure detail"))));
    }

    @Test
    void redisFailuresReachOuterFilter() throws Exception {
        when(service.authenticate(any(), any(), any()))
            .thenThrow(new RedisConnectionFailureException("private redis failure"));
        mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
            .andExpect(content().string(not(containsString("private redis failure"))));
    }

    @Test
    void wrappedRedisFailuresReachOuterFilter() throws Exception {
        when(service.authenticate(any(), any(), any())).thenThrow(new IllegalStateException("wrapper",
            new RedisConnectionFailureException("private redis failure")));
        mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"));
    }

    @Test
    void frameworkClientErrorsRetainStatus() throws Exception {
        when(service.authenticate(any(), any(), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password\"}"))
            .andExpect(status().isTooManyRequests());
    }
}
