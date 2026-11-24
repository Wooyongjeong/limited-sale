package com.wooyongjeong.limitedsale.order.presentation;

import com.wooyongjeong.limitedsale.member.api.MemberIdentity;
import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceResult;
import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceResult.Kind;
import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceService;
import com.wooyongjeong.limitedsale.order.domain.AcceptanceStatus;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptance;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderAcceptanceControllerMvcTest {
    private static final MemberIdentity MEMBER = () -> 7L;
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00.123456Z");
    private final OrderAcceptanceService acceptances = mock(OrderAcceptanceService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderAcceptanceController(acceptances))
        .setControllerAdvice(new OrderExceptionHandler()).build();
    private final TestingAuthenticationToken member = new TestingAuthenticationToken(MEMBER, null);
    private final UUID id = UUID.randomUUID();
    private final OrderAcceptance accepted = new OrderAcceptance(id, 3, 2, AcceptanceStatus.ACCEPTED, null, null,
        null, AT, null);

    @Test
    void aFirstAcceptanceAnswers202WithLocationStatusAndUrl() throws Exception {
        when(acceptances.accept(7, UUID.fromString("11111111-1111-1111-1111-111111111111"), 3, 2))
            .thenReturn(new OrderAcceptanceResult(Kind.ACCEPTED, accepted));

        mvc.perform(post("/api/v2/orders").principal(member).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "11111111-1111-1111-1111-111111111111")
                .content("{\"saleId\":3,\"quantity\":2}"))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", "/api/v2/order-requests/" + id))
            .andExpect(jsonPath("$.httpStatus").value(202))
            .andExpect(jsonPath("$.code").value("ORDER_ACCEPTED"))
            .andExpect(jsonPath("$.data.acceptanceId").value(id.toString()))
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.data.statusUrl").value("/api/v2/order-requests/" + id));
    }

    @Test
    void aReplayAnswers200WithoutLocation() throws Exception {
        when(acceptances.accept(anyLong(), any(), anyLong(), anyInt()))
            .thenReturn(new OrderAcceptanceResult(Kind.REPLAYED, accepted));

        mvc.perform(post("/api/v2/orders").principal(member).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"saleId\":3,\"quantity\":2}"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.code").value("ORDER_ACCEPTANCE_FOUND"))
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }

    @Test
    void rejectionsReuseTheVersionOneCodesAndCarryNoData() throws Exception {
        for (var row : new Object[][] {{Kind.SALE_NOT_FOUND, 404, "SALE_NOT_FOUND"},
                {Kind.SALE_OUTSIDE_PERIOD, 409, "SALE_OUTSIDE_PERIOD"}, {Kind.SALE_STOPPED, 409, "SALE_STOPPED"},
                {Kind.SALE_SOLD_OUT, 409, "SALE_SOLD_OUT"},
                {Kind.IDEMPOTENCY_CONFLICT, 409, "IDEMPOTENCY_CONFLICT"}}) {
            when(acceptances.accept(anyLong(), any(), anyLong(), anyInt()))
                .thenReturn(new OrderAcceptanceResult((Kind) row[0], null));

            mvc.perform(post("/api/v2/orders").principal(member).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .content("{\"saleId\":3,\"quantity\":2}"))
                .andExpect(status().is((Integer) row[1]))
                .andExpect(jsonPath("$.code").value(row[2]))
                .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    void aRateLimitedRequestCarriesRetryAfter() throws Exception {
        when(acceptances.accept(anyLong(), any(), anyLong(), anyInt()))
            .thenReturn(OrderAcceptanceResult.rateLimited(Duration.ofMillis(1500)));

        mvc.perform(post("/api/v2/orders").principal(member).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"saleId\":3,\"quantity\":2}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.code").value("ORDER_RATE_LIMITED"));
    }

    @Test
    void invalidInputIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v2/orders").principal(member).contentType(MediaType.APPLICATION_JSON)
                .content("{\"saleId\":3,\"quantity\":2}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data[0].field").value("Idempotency-Key"));
        mvc.perform(post("/api/v2/orders").principal(member).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"saleId\":3,\"quantity\":0}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data[0].field").value("quantity"));
        mvc.perform(post("/api/v2/orders").principal(member).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"saleId\":3,\"quantity\":1,\"extra\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/order-requests/not-a-uuid").principal(member))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data[0].field").value("acceptanceId"));
        verifyNoInteractions(acceptances);
    }

    @Test
    void theStatusResponseCarriesEveryFieldAndOrderIdWhenTheOrderExists() throws Exception {
        UUID orderId = UUID.randomUUID();
        var created = new OrderAcceptance(id, 3, 2, AcceptanceStatus.ORDER_CREATED, null, null, orderId, AT,
            AT.plusSeconds(1));
        when(acceptances.find(7, id)).thenReturn(new OrderAcceptanceResult(Kind.REPLAYED, created));

        mvc.perform(get("/api/v2/order-requests/{id}", id).principal(member))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("ORDER_ACCEPTANCE_FOUND"))
            .andExpect(jsonPath("$.data.acceptanceId").value(id.toString()))
            .andExpect(jsonPath("$.data.saleId").value(3))
            .andExpect(jsonPath("$.data.quantity").value(2))
            .andExpect(jsonPath("$.data.status").value("ORDER_CREATED"))
            .andExpect(jsonPath("$.data.rejectionReason").doesNotExist())
            .andExpect(jsonPath("$.data.failureReason").doesNotExist())
            .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.acceptedAt").exists())
            .andExpect(jsonPath("$.data.finishedAt").exists());
    }

    @Test
    void aMissingOrForeignAcceptanceIs404() throws Exception {
        when(acceptances.find(7, id)).thenReturn(new OrderAcceptanceResult(Kind.ACCEPTANCE_NOT_FOUND, null));

        mvc.perform(get("/api/v2/order-requests/{id}", id).principal(member))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ORDER_ACCEPTANCE_NOT_FOUND"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }
}
