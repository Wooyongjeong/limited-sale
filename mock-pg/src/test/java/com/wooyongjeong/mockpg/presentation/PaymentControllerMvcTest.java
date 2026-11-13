package com.wooyongjeong.mockpg.presentation;

import com.wooyongjeong.mockpg.application.PaymentService;
import com.wooyongjeong.mockpg.domain.PaymentStore;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerMvcTest {
    private final PaymentStore store = mock(PaymentStore.class);
    private final UUID key = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(new PaymentService(store)))
                .setControllerAdvice(new PaymentExceptionHandler()).build();
    }

    @Test
    void internalArgumentFailureIsServerErrorAcrossPaymentEndpoints() throws Exception {
        var failure = new IllegalArgumentException("private persistence detail");
        when(store.charge(any(), anyLong())).thenThrow(failure);
        when(store.close(any(), anyLong())).thenThrow(failure);
        when(store.query(any())).thenThrow(failure);
        when(store.configure(any(), any())).thenThrow(failure);
        var requests = java.util.List.of(
                post("/api/v1/payments").content("{\"pgKey\":\"" + key + "\",\"amount\":100}"),
                post("/api/v1/payments/" + key + "/close").content("{\"amount\":100}"),
                get("/api/v1/payments/" + key),
                put("/api/v1/simulations/" + key).content("{\"scenario\":\"SUCCESS\"}"));
        for (var request : requests) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.httpStatus").value(500))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("private persistence detail"))));
        }
    }

    @Test
    void malformedInputIsRejectedBeforeCallingStore() throws Exception {
        var requests = java.util.List.of(
                get("/api/v1/payments/not-a-uuid"),
                post("/api/v1/payments").content("{\"pgKey\":\"" + key + "\",\"amount\":0}"),
                put("/api/v1/simulations/" + key).content("{\"scenario\":\"unknown\"}"),
                put("/api/v1/simulations/" + key).content("{\"scenario\":\"DELAY\",\"delaySeconds\":601}"),
                put("/api/v1/simulations/" + key).content("{\"scenario\":\"SUCCESS\",\"delaySeconds\":1}"));
        for (var request : requests) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(store);
    }
}
