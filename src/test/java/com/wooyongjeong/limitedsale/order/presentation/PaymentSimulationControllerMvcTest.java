package com.wooyongjeong.limitedsale.order.presentation;

import com.wooyongjeong.limitedsale.order.application.PaymentSimulationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentSimulationControllerMvcTest {
    @Test
    void unexpectedServiceArgumentFailureReturnsInternalErrorWithoutDetails() throws Exception {
        var service = mock(PaymentSimulationService.class);
        when(service.configure(any(), any())).thenThrow(new IllegalArgumentException("private-service-detail"));
        var mvc = MockMvcBuilders.standaloneSetup(new PaymentSimulationController(service))
                .setControllerAdvice(new OrderExceptionHandler()).build();
        mvc.perform(put("/api/v1/admin/orders/{orderId}/payment-simulation", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"SUCCESS\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.httpStatus").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "private-service-detail"))));
    }
}
