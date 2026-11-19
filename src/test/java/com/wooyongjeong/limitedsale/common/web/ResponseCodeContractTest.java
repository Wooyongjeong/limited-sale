package com.wooyongjeong.limitedsale.common.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCodeContractTest {
    private static final Set<Integer> DOCUMENTED_STATUSES =
        Set.of(200, 201, 202, 400, 401, 403, 404, 409, 415, 429, 500, 503);
    /**
     * The same string means a 200 success for stopping a sale and a 409 rejection for ordering from a stopped
     * sale. The pairing is pinned here so a new collision fails; see docs/stages/01-12-followup-verification.md.
     */
    private static final Set<String> KNOWN_CROSS_ENUM_COLLISIONS = Set.of("SALE_STOPPED");

    private static List<Class<?>> responseCodeEnums() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(ResponseCode.class));
        List<Class<?>> types = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.wooyongjeong.limitedsale")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (!type.isInterface()) {
                types.add(type);
            }
        }
        return types;
    }

    @Test
    void everyDomainAndCommonCodeSetIsAnEnumImplementingTheInterface() throws Exception {
        var types = responseCodeEnums();
        assertThat(types).allSatisfy(type -> assertThat(type.isEnum()).as(type.getName()).isTrue());
        assertThat(types).extracting(Class::getSimpleName).contains("CommonResponseCode", "MemberResponseCode",
            "AuthenticationResponseCode", "ProductResponseCode", "SaleResponseCode", "OrderResponseCode",
            "SimulationResponseCode");
    }

    @Test
    void codesAreStableUpperSnakeNamesWithDocumentedStatusesAndMessages() throws Exception {
        Map<String, Integer> statusByCode = new HashMap<>();
        for (Class<?> type : responseCodeEnums()) {
            for (Object constant : type.getEnumConstants()) {
                var code = (ResponseCode) constant;
                String where = type.getSimpleName() + "." + code.code();
                assertThat(code.code()).as(where).isEqualTo(((Enum<?>) constant).name()).matches("[A-Z][A-Z0-9_]*");
                assertThat(DOCUMENTED_STATUSES).as(where + " status").contains(code.httpStatus());
                assertThat(code.message()).as(where + " message").isNotBlank();
                Integer previous = statusByCode.putIfAbsent(code.code(), code.httpStatus());
                if (previous != null && !KNOWN_CROSS_ENUM_COLLISIONS.contains(code.code())) {
                    assertThat(code.httpStatus()).as(where + " must keep the status of the same code elsewhere")
                        .isEqualTo(previous);
                }
            }
        }
    }
}
