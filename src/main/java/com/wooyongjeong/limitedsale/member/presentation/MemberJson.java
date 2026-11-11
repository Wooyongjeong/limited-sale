package com.wooyongjeong.limitedsale.member.presentation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Set;

final class MemberJson {
    private MemberJson() { }

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

    static class RegistrationDeserializer extends JsonDeserializer<MemberController.Registration> {
        @Override
        public MemberController.Registration deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = read(parser, Set.of("email", "password", "nickname"));
            return new MemberController.Registration(value(node, "email"), value(node, "password"),
                value(node, "nickname"));
        }

        @Override
        public MemberController.Registration getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(MemberController.Registration.class, "Expected one JSON object");
        }
    }

    static class LoginDeserializer extends JsonDeserializer<SessionController.Login> {
        @Override
        public SessionController.Login deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = read(parser, Set.of("email", "password"));
            return new SessionController.Login(value(node, "email"), value(node, "password"));
        }

        @Override
        public SessionController.Login getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(SessionController.Login.class, "Expected one JSON object");
        }
    }
}
