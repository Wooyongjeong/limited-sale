package com.wooyongjeong.limitedsale.member.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Set;

public final class MemberJson {
    private MemberJson() {
    }

    private static JsonNode read(JsonParser parser, Set<String> fields) throws IOException {
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode node = parser.getCodec().readTree(parser);
        if (node == null || !node.isObject() || parser.nextToken() != null) {
            throw JsonMappingException.from(parser, "Expected one JSON object");
        }
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode value = node.get(name);
            if (!fields.contains(name) || (!value.isNull() && !value.isTextual())) {
                throw JsonMappingException.from(parser, "Invalid request field");
            }
        }
        return node;
    }

    private static String value(JsonNode node, String name) {
        JsonNode field = node.get(name);
        return field == null || field.isNull() ? null : field.textValue();
    }

    public static class RegistrationDeserializer extends JsonDeserializer<MemberRegistrationRequest> {
        @Override
        public MemberRegistrationRequest deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = read(parser, Set.of("email", "password", "nickname"));
            return new MemberRegistrationRequest(value(node, "email"), value(node, "password"),
                value(node, "nickname"));
        }

        @Override
        public MemberRegistrationRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(MemberRegistrationRequest.class, "Expected one JSON object");
        }
    }

    public static class LoginDeserializer extends JsonDeserializer<LoginRequest> {
        @Override
        public LoginRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = read(parser, Set.of("email", "password"));
            return new LoginRequest(value(node, "email"), value(node, "password"));
        }

        @Override
        public LoginRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(LoginRequest.class, "Expected one JSON object");
        }
    }
}
