package com.wooyongjeong.limitedsale.order.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.member.api.MemberIdentity;
import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceResult;
import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceService;
import com.wooyongjeong.limitedsale.order.presentation.code.OrderResponseCode;
import com.wooyongjeong.limitedsale.order.presentation.request.OrderRequest;
import com.wooyongjeong.limitedsale.order.presentation.response.OrderAcceptanceResponse;
import com.wooyongjeong.limitedsale.order.presentation.response.OrderAcceptanceStatusResponse;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The asynchronous order API. It exists only when `limited-sale.order.async.enabled` is true; otherwise neither path is
 * mapped and both answer 404, so nothing can be stored through them.
 */
@RestController
@ConditionalOnProperty(name = "limited-sale.order.async.enabled", havingValue = "true")
public class OrderAcceptanceController {
    private final OrderAcceptanceService acceptances;

    public OrderAcceptanceController(OrderAcceptanceService acceptances) {
        this.acceptances = acceptances;
    }

    @PostMapping(path = "/api/v2/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<?>> accept(Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody OrderRequest body) {
        OrderJson.fields(body.value());
        long saleId = OrderJson.number(body.value(), "saleId", Long.MAX_VALUE);
        int quantity = (int) OrderJson.number(body.value(), "quantity", 1_000_000);
        return response(acceptances.accept(((MemberIdentity) authentication.getPrincipal()).id(),
            OrderJson.uuid(key, "Idempotency-Key"), saleId, quantity));
    }

    @GetMapping(path = "/api/v2/order-requests/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<?>> get(Authentication authentication, @PathVariable String id) {
        var result = acceptances.find(((MemberIdentity) authentication.getPrincipal()).id(),
            OrderJson.uuid(id, "acceptanceId"));
        if (result.kind() == OrderAcceptanceResult.Kind.ACCEPTANCE_NOT_FOUND) {
            return response(result);
        }
        return ResponseEntity.ok(ApiResponse.of(OrderResponseCode.ORDER_ACCEPTANCE_FOUND,
            OrderAcceptanceStatusResponse.from(result.acceptance())));
    }

    private ResponseEntity<ApiResponse<?>> response(OrderAcceptanceResult result) {
        OrderResponseCode code = switch (result.kind()) {
            case ACCEPTED -> OrderResponseCode.ORDER_ACCEPTED;
            case REPLAYED -> OrderResponseCode.ORDER_ACCEPTANCE_FOUND;
            case SALE_NOT_FOUND -> OrderResponseCode.SALE_NOT_FOUND;
            case SALE_OUTSIDE_PERIOD -> OrderResponseCode.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> OrderResponseCode.SALE_STOPPED;
            case PURCHASE_LIMIT_EXCEEDED -> OrderResponseCode.PURCHASE_LIMIT_EXCEEDED;
            case SALE_SOLD_OUT -> OrderResponseCode.SALE_SOLD_OUT;
            case IDEMPOTENCY_CONFLICT -> OrderResponseCode.IDEMPOTENCY_CONFLICT;
            case ACCEPTANCE_NOT_FOUND -> OrderResponseCode.ORDER_ACCEPTANCE_NOT_FOUND;
            case RATE_LIMITED -> OrderResponseCode.ORDER_RATE_LIMITED;
        };
        var body = result.acceptance() == null ? null : OrderAcceptanceResponse.from(result.acceptance());
        var builder = ResponseEntity.status(code.httpStatus());
        if (result.kind() == OrderAcceptanceResult.Kind.ACCEPTED) {
            builder.location(URI.create(body.statusUrl()));
        }
        if (result.kind() == OrderAcceptanceResult.Kind.RATE_LIMITED) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(result.retryAfter())));
        }
        return builder.body(ApiResponse.of(code, body));
    }

    /** Whole seconds, rounded up, because a smaller value would send the client back while the window is open. */
    private static long retryAfterSeconds(Duration retryAfter) {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
