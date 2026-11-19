package com.wooyongjeong.limitedsale.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.common.web.ResponseCode;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Asserts that a real HTTP response carries the status, code and message of one {@link ResponseCode}. */
public final class ResponseEnvelope {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponseEnvelope() {
    }

    public static void assertCode(HttpResponse<String> response, ResponseCode expected) throws Exception {
        var body = JSON.readTree(response.body());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected.httpStatus());
        assertThat(body.path("httpStatus").asInt()).as("body httpStatus").isEqualTo(expected.httpStatus());
        assertThat(body.path("code").asText()).isEqualTo(expected.code());
        assertThat(body.path("message").asText()).isEqualTo(expected.message());
    }
}
