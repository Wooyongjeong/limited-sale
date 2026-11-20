package com.wooyongjeong.mockpg.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;

final class RequestJson {
    private RequestJson() {
    }

    static JsonNode read(JsonParser parser) {
        JsonNode node = parser.readValueAsTree();
        if (node == null || !node.isObject() || parser.nextToken() != null) {
            throw DatabindException.from(parser, "Expected one JSON object");
        }
        return node;
    }
}
