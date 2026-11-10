package com.wooyongjeong.limitedsale.member.presentation;

import com.wooyongjeong.limitedsale.member.application.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemberMediaTypeTest {
    private final MemberService service = mock(MemberService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MemberController(service))
            .setControllerAdvice(new MemberExceptionHandler()).build();
    }

    @Test
    void unsupportedContentTypeReturns415WithoutCallingService() throws Exception {
        mvc.perform(post("/api/v1/members").contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.APPLICATION_JSON).content("hello"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.httpStatus").value(415))
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedAcceptReturns406BeforeCallingService() throws Exception {
        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML)
                .content("{\"email\":\"test@example.com\",\"password\":\"password\"}"))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().string(""));
        verifyNoInteractions(service);
    }

    @Test
    void unexpectedFailureRemains500WithoutExposingInternalDetails() throws Exception {
        when(service.register(any(), any(), any()))
            .thenThrow(new IllegalStateException("private implementation detail"));
        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"password\",\"nickname\":\"name\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.httpStatus").value(500))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다"));
    }

    @Test
    void frameworkClientErrorKeepsItsStatus() throws Exception {
        when(service.register(any(), any(), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"password\",\"nickname\":\"name\"}"))
            .andExpect(status().isTooManyRequests());
    }
}
