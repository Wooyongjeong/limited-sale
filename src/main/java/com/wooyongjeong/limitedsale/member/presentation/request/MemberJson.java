package com.wooyongjeong.limitedsale.member.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import java.util.Set;

public final class MemberJson {
    private MemberJson() {
    }

    private static JsonNode read(JsonParser parser, Set<String> fields) {
        JsonNode node = parser.readValueAsTree();
        if (node == null || !node.isObject() || parser.nextToken() != null) {
            throw DatabindException.from(parser, "Expected one JSON object");
        }
        for (String name : node.propertyNames()) {
            JsonNode value = node.get(name);
            if (!fields.contains(name) || (!value.isNull() && !value.isString())) {
                throw DatabindException.from(parser, "Invalid request field");
            }
        }
        return node;
    }

    private static String value(JsonNode node, String name) {
        JsonNode field = node.get(name);
        return field == null || field.isNull() ? null : field.stringValue();
    }

    public static class RegistrationDeserializer extends ValueDeserializer<MemberRegistrationRequest> {
        @Override
        public MemberRegistrationRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode node = read(parser, Set.of("email", "password", "nickname"));
            return new MemberRegistrationRequest(value(node, "email"), value(node, "password"),
                value(node, "nickname"));
        }

        @Override
        public MemberRegistrationRequest getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(MemberRegistrationRequest.class, "Expected one JSON object");
        }
    }

    public static class LoginDeserializer extends ValueDeserializer<LoginRequest> {
        @Override
        public LoginRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode node = read(parser, Set.of("email", "password"));
            return new LoginRequest(value(node, "email"), value(node, "password"));
        }

        @Override
        public LoginRequest getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(LoginRequest.class, "Expected one JSON object");
        }
    }
}
