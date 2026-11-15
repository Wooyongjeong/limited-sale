package com.wooyongjeong.limitedsale.order.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.member.api.MemberIdentity;
import com.wooyongjeong.limitedsale.order.application.OrderPaymentService;
import com.wooyongjeong.limitedsale.order.application.OrderResult;
import com.wooyongjeong.limitedsale.order.application.OrderService;
import com.wooyongjeong.limitedsale.order.presentation.request.OrderRequest;
import com.wooyongjeong.limitedsale.order.presentation.request.PaymentRequest;
import com.wooyongjeong.limitedsale.order.presentation.response.OrderViewResponse;
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
    private final OrderPaymentService payments;

    public OrderController(OrderService orders, OrderPaymentService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @PostMapping(path = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<?>> create(Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody OrderRequest body) {
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

    @PostMapping(path = "/api/v1/orders/{id}/payment", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<?>> pay(Authentication authentication, @PathVariable String id,
            @RequestBody(required = false) PaymentRequest body) {
        if (body != null) {
            throw new InvalidOrderInput("body", "결제 요청 본문은 허용되지 않습니다");
        }
        return response(payments.pay(((MemberIdentity) authentication.getPrincipal()).id(),
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
            case PAYMENT_SUCCEEDED -> OrderResponseCode.PAYMENT_SUCCEEDED;
            case PAYMENT_PENDING -> OrderResponseCode.PAYMENT_PENDING;
            case PAYMENT_DECLINED -> OrderResponseCode.PAYMENT_DECLINED;
            case ORDER_EXPIRED -> OrderResponseCode.ORDER_EXPIRED;
            case ORDER_NOT_FOUND -> OrderResponseCode.ORDER_NOT_FOUND;
        };
        var order = result.order() == null || result.kind() == OrderResult.Kind.PAYMENT_DECLINED
            || result.kind() == OrderResult.Kind.ORDER_EXPIRED ? null : OrderViewResponse.from(result.order());
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.of(code, order));
    }
}
