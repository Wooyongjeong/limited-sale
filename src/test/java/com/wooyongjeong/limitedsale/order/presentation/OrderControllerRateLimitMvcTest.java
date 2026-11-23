package com.wooyongjeong.limitedsale.order.presentation;

import com.wooyongjeong.limitedsale.member.api.MemberIdentity;
import com.wooyongjeong.limitedsale.order.application.OrderPaymentService;
import com.wooyongjeong.limitedsale.order.application.OrderResult;
import com.wooyongjeong.limitedsale.order.application.OrderService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerRateLimitMvcTest {
    private static final MemberIdentity MEMBER = () -> 7L;
    private final OrderService orders = mock(OrderService.class);
    private final OrderPaymentService payments = mock(OrderPaymentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders, payments))
        .setControllerAdvice(new OrderExceptionHandler()).build();
    private final TestingAuthenticationToken member = new TestingAuthenticationToken(MEMBER, null);

    @ParameterizedTest
    @CsvSource({"1,1", "400,1", "999,1", "1000,1", "1001,2", "2000,2", "2999,3"})
    void retryAfterIsTheRemainingTimeInWholeSecondsRoundedUpWithAMinimumOfOne(long remainingMillis, String seconds)
            throws Exception {
        when(orders.create(anyLong(), any(), anyLong(), anyInt()))
            .thenReturn(OrderResult.rateLimited(Duration.ofMillis(remainingMillis)));
        mvc.perform(post("/api/v1/orders").principal(member).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"saleId\":1,\"quantity\":1}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", seconds))
            .andExpect(jsonPath("$.httpStatus").value(429))
            .andExpect(jsonPath("$.code").value("ORDER_RATE_LIMITED"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void paymentRejectionUsesTheSameResponse() throws Exception {
        when(payments.pay(anyLong(), any())).thenReturn(OrderResult.rateLimited(Duration.ofMillis(1500)));
        mvc.perform(post("/api/v1/orders/{id}/payment", UUID.randomUUID()).principal(member))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.code").value("ORDER_RATE_LIMITED"));
    }
}
