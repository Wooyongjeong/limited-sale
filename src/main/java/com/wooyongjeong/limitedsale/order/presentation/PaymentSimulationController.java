package com.wooyongjeong.limitedsale.order.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.common.web.CommonResponseCode;
import com.wooyongjeong.limitedsale.order.application.PaymentSimulationService;
import com.wooyongjeong.limitedsale.order.presentation.request.PaymentSimulationRequest;
import com.wooyongjeong.limitedsale.order.presentation.response.PaymentSimulationResponse;
import com.wooyongjeong.limitedsale.payment.api.SimulationConfiguration;
import com.wooyongjeong.limitedsale.payment.api.SimulationConflict;
import com.wooyongjeong.limitedsale.payment.api.SimulationUnavailable;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "test"})
public class PaymentSimulationController {
    private final PaymentSimulationService service;

    public PaymentSimulationController(PaymentSimulationService service) {
        this.service = service;
    }

    @PutMapping("/api/v1/admin/orders/{orderId}/payment-simulation")
    public ResponseEntity<ApiResponse<?>> configure(@PathVariable String orderId,
            @RequestBody PaymentSimulationRequest request) {
        UUID id = OrderJson.uuid(orderId, "orderId");
        JsonNode body = request.value();
        body.fieldNames().forEachRemaining(name -> {
            if (!Set.of("scenario", "delaySeconds").contains(name) || body.get(name).isNull()) {
                throw new InvalidOrderInput("body", "입력값을 확인하세요");
            }
        });
        JsonNode scenarioNode = body.get("scenario");
        if (scenarioNode == null || !scenarioNode.isTextual()) {
            throw new InvalidOrderInput("body", "입력값을 확인하세요");
        }
        String scenario = scenarioNode.textValue();
        JsonNode delayNode = body.get("delaySeconds");
        if (delayNode != null && (!delayNode.isIntegralNumber() || !delayNode.canConvertToInt())) {
            throw new InvalidOrderInput("body", "입력값을 확인하세요");
        }
        Integer delay = delayNode == null ? null : delayNode.intValue();
        switch (scenario) {
            case "SUCCESS", "DECLINE" -> {
                if (delay != null) {
                    throw new InvalidOrderInput("body", "입력값을 확인하세요");
                }
            }
            case "DELAY", "RESPONSE_LOSS" -> {
                if (delay == null) {
                    delay = scenario.equals("DELAY") ? 10 : 5;
                }
                if (delay < 1 || delay > 600) {
                    throw new InvalidOrderInput("body", "입력값을 확인하세요");
                }
            }
            default -> throw new InvalidOrderInput("body", "입력값을 확인하세요");
        }
        if (!service.configure(id, new SimulationConfiguration(scenario, delay))) {
            return ResponseEntity.status(404).body(ApiResponse.of(OrderResponseCode.ORDER_NOT_FOUND, null));
        }
        return ResponseEntity.ok(ApiResponse.of(SimulationResponseCode.SIMULATION_CONFIGURED,
                new PaymentSimulationResponse(id, scenario, delay)));
    }

    @ExceptionHandler({InvalidOrderInput.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<?>> invalid() {
        return ResponseEntity.badRequest().body(ApiResponse.of(CommonResponseCode.INVALID_INPUT, null));
    }

    @ExceptionHandler(SimulationConflict.class)
    public ResponseEntity<ApiResponse<?>> conflict() {
        return ResponseEntity.status(409).body(ApiResponse.of(SimulationResponseCode.SIMULATION_CONFLICT, null));
    }

    @ExceptionHandler(SimulationUnavailable.class)
    public ResponseEntity<ApiResponse<?>> unavailable() {
        return ResponseEntity.status(503).body(ApiResponse.of(SimulationResponseCode.SIMULATION_UNAVAILABLE, null));
    }
}
