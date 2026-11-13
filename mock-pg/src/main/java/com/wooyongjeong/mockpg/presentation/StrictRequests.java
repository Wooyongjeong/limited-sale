package com.wooyongjeong.mockpg.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.wooyongjeong.mockpg.domain.Scenario;
import com.wooyongjeong.mockpg.domain.ScenarioConfiguration;
import java.util.Set;
import java.util.UUID;

final class StrictRequests {
    private StrictRequests() {
    }

    static UUID chargeKey(JsonNode root) {
        fields(root, Set.of("pgKey", "amount"));
        return uuid(text(root, "pgKey"));
    }

    static long paymentAmount(JsonNode root, boolean charge) {
        fields(root, charge ? Set.of("pgKey", "amount") : Set.of("amount"));
        return amount(root);
    }

    static ScenarioConfiguration simulation(JsonNode root) {
        fields(root, Set.of("scenario", "delaySeconds"));
        Scenario scenario;
        try {
            scenario = Scenario.valueOf(text(root, "scenario"));
        } catch (IllegalArgumentException failure) {
            throw new InvalidPaymentInput();
        }
        JsonNode delay = root.get("delaySeconds");
        if (delay != null && (!delay.isIntegralNumber() || !delay.canConvertToInt())) {
            throw new InvalidPaymentInput();
        }
        try {
            return new ScenarioConfiguration(scenario, delay == null ? null : delay.intValue());
        } catch (IllegalArgumentException failure) {
            throw new InvalidPaymentInput();
        }
    }

    static UUID uuid(String value) {
        UUID key;
        try {
            key = UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw new InvalidPaymentInput();
        }
        if (!key.toString().equalsIgnoreCase(value)) {
            throw new InvalidPaymentInput();
        }
        return key;
    }

    private static void fields(JsonNode root, Set<String> fields) {
        root.fields().forEachRemaining(field -> {
            if (!fields.contains(field.getKey()) || field.getValue().isNull()) {
                throw new InvalidPaymentInput();
            }
        });
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw new InvalidPaymentInput();
        }
        return node.textValue();
    }

    private static long amount(JsonNode root) {
        JsonNode node = root.get("amount");
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() < 1 || node.longValue() > 1_000_000_000_000_000L) {
            throw new InvalidPaymentInput();
        }
        return node.longValue();
    }
}
