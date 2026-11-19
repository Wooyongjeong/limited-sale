package com.wooyongjeong.limitedsale.common.observation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String header, List<String> seenInChain) throws Exception {
        var request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader("X-Request-Id", header);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> seenInChain.add(MDC.get("requestId")));
        return response.getHeader("X-Request-Id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "client-123_ABC", "0123456789012345678901234567890123456789012345678901234567890123"})
    void aSafeClientValueIsEchoedAndLogged(String id) throws Exception {
        var seen = new ArrayList<String>();
        assertThat(run(id, seen)).isEqualTo(id);
        assertThat(seen).containsExactly(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "line\\nbreak", "한글", "semi;colon", "dot.dot",
        "01234567890123456789012345678901234567890123456789012345678901234"})
    void anUnsafeClientValueIsReplacedByAUuid(String id) throws Exception {
        var seen = new ArrayList<String>();
        String echoed = run(id, seen);
        assertThat(UUID.fromString(echoed)).isNotNull();
        assertThat(seen).containsExactly(echoed);
    }

    @Test
    void aMissingValueGetsAFreshUuidPerRequest() throws Exception {
        String first = run(null, new ArrayList<>());
        String second = run(null, new ArrayList<>());
        assertThat(UUID.fromString(first)).isNotEqualTo(UUID.fromString(second));
    }

    @Test
    void theMdcIsClearedAfterTheChainEvenWhenItFails() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
            (req, res) -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("requestId")).isNull();
    }
}
