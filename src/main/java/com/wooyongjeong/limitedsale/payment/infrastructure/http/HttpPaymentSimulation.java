package com.wooyongjeong.limitedsale.payment.infrastructure.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.payment.api.PaymentSimulation;
import com.wooyongjeong.limitedsale.payment.api.SimulationConfiguration;
import com.wooyongjeong.limitedsale.payment.api.SimulationConflict;
import com.wooyongjeong.limitedsale.payment.api.SimulationUnavailable;
import com.wooyongjeong.limitedsale.payment.infrastructure.persistence.PaymentKeyLookup;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"local", "test"})
public class HttpPaymentSimulation implements PaymentSimulation {
    private final PaymentKeyLookup keys;
    private final Environment environment;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HttpPaymentSimulation(PaymentKeyLookup keys, Environment environment, ObjectMapper mapper) {
        this.keys = keys;
        this.environment = environment;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public SimulationConfiguration configure(long orderId, SimulationConfiguration configuration) {
        UUID key = keys.find(orderId);
        String secret = environment.getProperty("MOCK_PG_API_KEY");
        if (secret == null || secret.isBlank()) {
            throw new SimulationUnavailable();
        }
        String baseUrl = environment.getProperty("MOCK_PG_BASE_URL", "http://localhost:8081");
        String delay = configuration.delaySeconds() == null ? "" : ",\"delaySeconds\":" + configuration.delaySeconds();
        String json = "{\"scenario\":\"" + configuration.scenario() + "\"" + delay + "}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/simulations/" + key))
                    .timeout(Duration.ofSeconds(3)).header("X-Mock-PG-Key", secret)
                    .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                throw new SimulationConflict();
            }
            if (response.statusCode() != 200) {
                throw new SimulationUnavailable();
            }
            JsonNode responseBody = mapper.readTree(response.body());
            if (responseBody == null || !responseBody.path("httpStatus").isIntegralNumber()
                    || !responseBody.path("httpStatus").canConvertToInt()
                    || responseBody.path("httpStatus").intValue() != 200
                    || !"OK".equals(responseBody.path("code").asText())) {
                throw new SimulationUnavailable();
            }
            JsonNode data = responseBody.path("data");
            JsonNode actualDelay = data.path("delaySeconds");
            if (!configuration.scenario().equals(data.path("scenario").asText())
                    || (configuration.delaySeconds() == null && !actualDelay.isNull())
                    || (configuration.delaySeconds() != null && (!actualDelay.isIntegralNumber()
                    || !actualDelay.canConvertToInt()
                    || !configuration.delaySeconds().equals(actualDelay.intValue())))) {
                throw new SimulationUnavailable();
            }
            return configuration;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SimulationUnavailable();
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new SimulationUnavailable();
        }
    }
}
