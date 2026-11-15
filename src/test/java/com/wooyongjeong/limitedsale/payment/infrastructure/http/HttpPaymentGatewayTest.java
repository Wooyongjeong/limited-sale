package com.wooyongjeong.limitedsale.payment.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class HttpPaymentGatewayTest {
    @Test
    void timeoutIncludesAResponseBodyThatStallsAfterHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 10);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(1200);
                exchange.getResponseBody().write("0123456789".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var env = new MockEnvironment().withProperty("MOCK_PG_API_KEY", "secret")
                    .withProperty("MOCK_PG_BASE_URL", "http://localhost:" + server.getAddress().getPort());
            var gateway = new HttpPaymentGateway(env, new ObjectMapper());
            long start = System.nanoTime();
            assertThat(gateway.query(new PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING"),
                    Duration.ofMillis(250))).isEqualTo(GatewayResult.UNKNOWN);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesKeyAmountAndResponseEnvelope() throws Exception {
        UUID key = UUID.randomUUID();
        var response = new AtomicReference<>("{\"httpStatus\":200,\"code\":\"OK\",\"data\":{\"pgKey\":\""
                + key + "\",\"amount\":1000,\"status\":\"SUCCEEDED\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"finalizedAt\":\"2026-01-01T00:00:00Z\"},\"message\":\"OK\"}");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var env = new MockEnvironment().withProperty("MOCK_PG_API_KEY", "secret")
                    .withProperty("MOCK_PG_BASE_URL", "http://localhost:" + server.getAddress().getPort());
            var gateway = new HttpPaymentGateway(env, new ObjectMapper());
            var payment = new PaymentDetails(key, 1000, "PROCESSING");
            assertThat(gateway.query(payment, Duration.ofSeconds(2))).isEqualTo(GatewayResult.SUCCEEDED);
            response.set(response.get().replace("2026-01-01T00:00:00Z", "invalid"));
            assertThat(gateway.query(payment, Duration.ofSeconds(2))).isEqualTo(GatewayResult.UNKNOWN);
            response.set(response.get().replace("invalid", "2026-01-01T00:00:00Z").replace("1000", "1001"));
            assertThat(gateway.query(payment, Duration.ofSeconds(2))).isEqualTo(GatewayResult.UNKNOWN);
            response.set("{\"httpStatus\":200,\"httpStatus\":200}");
            assertThat(gateway.query(payment, Duration.ofSeconds(2))).isEqualTo(GatewayResult.UNKNOWN);
        } finally {
            server.stop(0);
        }
    }
}
