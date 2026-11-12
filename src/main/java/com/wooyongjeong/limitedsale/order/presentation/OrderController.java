package com.wooyongjeong.limitedsale.order.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.member.api.MemberIdentity;
import com.wooyongjeong.limitedsale.order.application.OrderResult;
import com.wooyongjeong.limitedsale.order.application.OrderService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping(path = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<?>> create(Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody OrderJson.Body body) {
        OrderJson.fields(body.value());
        long saleId = OrderJson.number(body.value(), "saleId", Long.MAX_VALUE);
        int quantity = (int) OrderJson.number(body.value(), "quantity", 1_000_000);
        return response(orders.create(((MemberIdentity) authentication.getPrincipal()).id(),
            OrderJson.uuid(key, "Idempotency-Key"), saleId, quantity));
    }

    @GetMapping(path = "/api/v1/orders/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<?>> get(Authentication authentication, @PathVariable String id) {
        return response(orders.find(((MemberIdentity) authentication.getPrincipal()).id(),
            OrderJson.uuid(id, "orderId")));
    }

    private ResponseEntity<ApiResponse<?>> response(OrderResult result) {
        OrderResponseCode code = switch (result.kind()) {
            case CREATED -> OrderResponseCode.ORDER_CREATED;
            case REPLAYED -> OrderResponseCode.ORDER_FOUND;
            case SALE_NOT_FOUND -> OrderResponseCode.SALE_NOT_FOUND;
            case SALE_OUTSIDE_PERIOD -> OrderResponseCode.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> OrderResponseCode.SALE_STOPPED;
            case PURCHASE_LIMIT_EXCEEDED -> OrderResponseCode.PURCHASE_LIMIT_EXCEEDED;
            case SALE_SOLD_OUT -> OrderResponseCode.SALE_SOLD_OUT;
            case IDEMPOTENCY_CONFLICT -> OrderResponseCode.IDEMPOTENCY_CONFLICT;
            case ORDER_NOT_FOUND -> OrderResponseCode.ORDER_NOT_FOUND;
        };
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.of(code, result.order()));
    }
}
