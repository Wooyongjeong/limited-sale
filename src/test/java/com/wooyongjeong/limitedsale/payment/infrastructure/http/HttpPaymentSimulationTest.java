package com.wooyongjeong.limitedsale.payment.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wooyongjeong.limitedsale.payment.api.SimulationConfiguration;
import com.wooyongjeong.limitedsale.payment.api.SimulationUnavailable;
import com.wooyongjeong.limitedsale.payment.infrastructure.persistence.PaymentKeyLookup;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpPaymentSimulationTest {
    @Test
    void invalidPgAddressIsUnavailable() {
        var keys = mock(PaymentKeyLookup.class);
        when(keys.find(1)).thenReturn(UUID.randomUUID());
        var environment = new MockEnvironment().withProperty("MOCK_PG_API_KEY", "test-secret")
                .withProperty("MOCK_PG_BASE_URL", "not a valid address");
        var client = new HttpPaymentSimulation(keys, environment, new ObjectMapper());
        assertThatThrownBy(() -> client.configure(1, new SimulationConfiguration("SUCCESS", null)))
                .isInstanceOf(SimulationUnavailable.class);
    }

    @Test
    void outOfRangePgDelayCannotBeTruncatedToExpectedValue() throws Exception {
        for (String delay : new String[] {"4294967301", "-4294967291", "18446744073709551621"}) {
            assertThatThrownBy(() -> configureWithResponseDelay(delay)).isInstanceOf(SimulationUnavailable.class);
        }
    }

    @Test
    void validPgDelayRemainsAccepted() throws Exception {
        assertThat(configureWithResponseDelay("5")).isEqualTo(new SimulationConfiguration("RESPONSE_LOSS", 5));
    }

    private SimulationConfiguration configureWithResponseDelay(String delay) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("{\"httpStatus\":200,\"code\":\"OK\",\"data\":{\"scenario\":\"RESPONSE_LOSS\","
                    + "\"delaySeconds\":" + delay + "}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        try {
            var keys = mock(PaymentKeyLookup.class);
            when(keys.find(1)).thenReturn(UUID.randomUUID());
            var environment = new MockEnvironment().withProperty("MOCK_PG_API_KEY", "test-secret")
                    .withProperty("MOCK_PG_BASE_URL", "http://localhost:" + server.getAddress().getPort());
            return new HttpPaymentSimulation(keys, environment, new ObjectMapper())
                    .configure(1, new SimulationConfiguration("RESPONSE_LOSS", 5));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedSuccessResponseIsUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"httpStatus\":\"200\",\"code\":\"OK\",\"data\":{\"scenario\":\"SUCCESS\",\"delaySeconds\":null}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        try {
            var keys = mock(PaymentKeyLookup.class);
            when(keys.find(1)).thenReturn(UUID.randomUUID());
            var environment = new MockEnvironment().withProperty("MOCK_PG_API_KEY", "test-secret")
                    .withProperty("MOCK_PG_BASE_URL", "http://localhost:" + server.getAddress().getPort());
            var client = new HttpPaymentSimulation(keys, environment, new ObjectMapper());
            assertThatThrownBy(() -> client.configure(1, new SimulationConfiguration("SUCCESS", null)))
                    .isInstanceOf(SimulationUnavailable.class);
        } finally {
            server.stop(0);
        }
    }
}
