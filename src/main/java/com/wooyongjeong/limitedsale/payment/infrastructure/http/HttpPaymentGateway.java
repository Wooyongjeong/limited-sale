package com.wooyongjeong.limitedsale.payment.infrastructure.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import com.wooyongjeong.limitedsale.payment.api.PaymentGateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.http.Outcome;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class HttpPaymentGateway implements PaymentGateway {
    private final Environment environment;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final HttpClient client = HttpClient.newHttpClient();

    public HttpPaymentGateway(Environment environment, ObjectMapper mapper, MeterRegistry registry) {
        this.environment = environment;
        this.registry = registry;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public GatewayResult charge(PaymentDetails payment, Duration timeout) {
        return send(payment, timeout, "POST", "/api/v1/payments", "{\"pgKey\":\"" + payment.key()
                + "\",\"amount\":" + payment.amount() + "}", false);
    }

    @Override
    public GatewayResult query(PaymentDetails payment, Duration timeout) {
        return send(payment, timeout, "GET", "/api/v1/payments/" + payment.key(), null, true);
    }

    @Override
    public GatewayResult close(PaymentDetails payment, Duration timeout) {
        return send(payment, timeout, "POST", "/api/v1/payments/" + payment.key() + "/close",
                "{\"amount\":" + payment.amount() + "}", false);
    }

    private GatewayResult send(PaymentDetails payment, Duration timeout, String method, String path, String body,
            boolean allowMissing) {
        String secret = environment.getProperty("MOCK_PG_API_KEY");
        if (secret == null || secret.isBlank() || timeout.isZero() || timeout.isNegative()) {
            return GatewayResult.UNKNOWN;
        }
        try {
            String base = environment.getProperty("MOCK_PG_BASE_URL", "http://localhost:8081");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("X-Mock-PG-Key", secret);
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            HttpRequest request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response;
            try {
                response = exchange(request, timeout, path.replace(payment.key().toString(), "{pgKey}"));
            } catch (TimeoutException failure) {
                return GatewayResult.UNKNOWN;
            }
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject() || !json.path("httpStatus").isIntegralNumber()
                    || !json.path("httpStatus").canConvertToInt() || json.path("httpStatus").intValue() != response.statusCode()
                    || !json.path("code").isTextual() || !json.path("message").isTextual() || json.size() != 4) {
                return GatewayResult.UNKNOWN;
            }
            if (response.statusCode() == 404 && allowMissing
                    && "NOT_FOUND".equals(json.path("code").textValue())
                    && json.path("data").isNull()) {
                return GatewayResult.MISSING;
            }
            boolean ok = response.statusCode() == 200 && "OK".equals(json.path("code").textValue());
            boolean processing = response.statusCode() == 202 && "PROCESSING".equals(json.path("code").textValue());
            JsonNode data = json.path("data");
            if ((!ok && !processing) || !data.isObject() || !data.path("pgKey").isTextual()
                    || !payment.key().toString().equalsIgnoreCase(data.path("pgKey").textValue())
                    || !data.path("amount").isIntegralNumber() || !data.path("amount").canConvertToLong()
                    || payment.amount() != data.path("amount").longValue() || !data.path("status").isTextual()
                    || data.size() != 6 || !data.path("createdAt").isTextual() || !data.path("updatedAt").isTextual()
                    || !data.has("finalizedAt")) {
                return GatewayResult.UNKNOWN;
            }
            Instant.parse(data.path("createdAt").textValue());
            Instant.parse(data.path("updatedAt").textValue());
            String status = data.path("status").textValue();
            if (processing && !data.path("finalizedAt").isNull()) {
                return GatewayResult.UNKNOWN;
            }
            if (ok) {
                if (!data.path("finalizedAt").isTextual()) {
                    return GatewayResult.UNKNOWN;
                }
                Instant.parse(data.path("finalizedAt").textValue());
            }
            if (processing && "PROCESSING".equals(status)) {
                return GatewayResult.PROCESSING;
            }
            if (ok && ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CLOSED".equals(status))) {
                return GatewayResult.valueOf(status);
            }
            return GatewayResult.UNKNOWN;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return GatewayResult.UNKNOWN;
        } catch (IOException | IllegalArgumentException | DateTimeParseException | ExecutionException failure) {
            return GatewayResult.UNKNOWN;
        }
    }

    /** Sends the request and records Spring's standard {@code http.client.requests} timer for the exchange. */
    private HttpResponse<String> exchange(HttpRequest request, Duration timeout, String uriTemplate)
            throws InterruptedException, ExecutionException, TimeoutException {
        long started = System.nanoTime();
        var responseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        String status = "IO_ERROR";
        Outcome outcome = Outcome.UNKNOWN;
        Throwable error = null;
        try {
            HttpResponse<String> response = responseFuture.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            status = Integer.toString(response.statusCode());
            outcome = Outcome.forStatus(response.statusCode());
            return response;
        } catch (TimeoutException | InterruptedException failure) {
            responseFuture.cancel(true);
            error = failure;
            throw failure;
        } catch (ExecutionException failure) {
            error = failure.getCause();
            throw failure;
        } finally {
            Timer.builder("http.client.requests").description("Outgoing PG HTTP requests")
                    .tag("client.name", request.uri().getHost()).tag("method", request.method())
                    .tag("uri", uriTemplate).tag("status", status).tag("outcome", outcome.name())
                    .tag("exception", error == null ? "none" : error.getClass().getSimpleName())
                    .register(registry).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
