package com.wooyongjeong.mockpg.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

final class RequestJson {
    private RequestJson() {
    }

    static JsonNode read(JsonParser parser) throws IOException {
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode node = parser.getCodec().readTree(parser);
        if (node == null || !node.isObject() || parser.nextToken() != null) {
            throw JsonMappingException.from(parser, "Expected one JSON object");
        }
        return node;
    }
}
