package com.wooyongjeong.limitedsale.support;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.wooyongjeong.limitedsale.common.web.ResponseCode;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Asserts that a real HTTP response carries the status, code and message of one {@link ResponseCode}. */
public final class ResponseEnvelope {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private ResponseEnvelope() {
    }

    public static void assertCode(HttpResponse<String> response, ResponseCode expected) throws Exception {
        var body = JSON.readTree(response.body());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected.httpStatus());
        assertThat(body.path("httpStatus").asInt()).as("body httpStatus").isEqualTo(expected.httpStatus());
        assertThat(body.path("code").asString()).isEqualTo(expected.code());
        assertThat(body.path("message").asString()).isEqualTo(expected.message());
    }
}
