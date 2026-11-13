package com.wooyongjeong.mockpg.presentation;

import com.wooyongjeong.mockpg.application.PaymentService;
import com.wooyongjeong.mockpg.common.web.ApiResponse;
import com.wooyongjeong.mockpg.domain.PaymentConflict;
import com.wooyongjeong.mockpg.domain.PaymentMissing;
import com.wooyongjeong.mockpg.domain.PaymentResult;
import com.wooyongjeong.mockpg.presentation.request.ChargeRequest;
import com.wooyongjeong.mockpg.presentation.request.CloseRequest;
import com.wooyongjeong.mockpg.presentation.request.SimulationRequest;
import com.wooyongjeong.mockpg.presentation.response.PaymentResponse;
import com.wooyongjeong.mockpg.presentation.response.SimulationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {
    private final PaymentService operations;

    public PaymentController(PaymentService operations) {
        this.operations = operations;
    }

    @PostMapping("/api/v1/payments")
    public ResponseEntity<ApiResponse<PaymentResponse>> charge(@RequestBody ChargeRequest body)
            throws InterruptedException {
        PaymentResult result = operations.charge(StrictRequests.chargeKey(body.value()),
                StrictRequests.paymentAmount(body.value(), true));
        return paymentResponse(result);
    }

    @GetMapping("/api/v1/payments/{key}")
    public ResponseEntity<ApiResponse<PaymentResponse>> query(@PathVariable String key) {
        return paymentResponse(operations.query(StrictRequests.uuid(key)));
    }

    @PostMapping("/api/v1/payments/{key}/close")
    public ResponseEntity<ApiResponse<PaymentResponse>> close(@PathVariable String key,
            @RequestBody CloseRequest body) {
        return paymentResponse(operations.close(StrictRequests.uuid(key),
                StrictRequests.paymentAmount(body.value(), false)));
    }

    @PutMapping("/api/v1/simulations/{key}")
    public ApiResponse<SimulationResponse> configure(@PathVariable String key, @RequestBody SimulationRequest body) {
        var result = operations.configure(StrictRequests.uuid(key), StrictRequests.simulation(body.value()));
        return ApiResponse.of(PaymentResponseCode.OK,
                new SimulationResponse(result.scenario().name(), result.delaySeconds()));
    }

    @ExceptionHandler({InvalidPaymentInput.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> badRequest() {
        return error(PaymentResponseCode.INVALID_INPUT);
    }

    @ExceptionHandler(PaymentConflict.class)
    public ResponseEntity<ApiResponse<Void>> conflict() {
        return error(PaymentResponseCode.CONFLICT);
    }

    @ExceptionHandler(PaymentMissing.class)
    public ResponseEntity<ApiResponse<Void>> missing() {
        return error(PaymentResponseCode.NOT_FOUND);
    }

    private ResponseEntity<ApiResponse<PaymentResponse>> paymentResponse(PaymentResult result) {
        PaymentResponseCode code = result.status().equals("PROCESSING")
                ? PaymentResponseCode.PROCESSING : PaymentResponseCode.OK;
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.of(code,
                new PaymentResponse(result.pgKey(), result.amount(), result.status(), result.createdAt(),
                        result.updatedAt(), result.finalizedAt())));
    }

    private ResponseEntity<ApiResponse<Void>> error(PaymentResponseCode code) {
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.of(code, null));
    }
}
