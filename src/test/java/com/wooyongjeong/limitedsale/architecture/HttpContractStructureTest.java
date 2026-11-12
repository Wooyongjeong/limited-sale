package com.wooyongjeong.limitedsale.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.wooyongjeong.limitedsale.member.domain.Member;
import com.wooyongjeong.limitedsale.member.domain.MemberRole;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

class HttpContractStructureTest {
    private static final String ROOT = "com.wooyongjeong.limitedsale";

    @Test
    void controllersAndResponseRecordsKeepHttpTypesAtPresentationBoundary() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
        List<String> violations = new ArrayList<>();
        classes.stream().map(type -> type.reflect()).forEach(type -> {
            if (type.isAnnotationPresent(RestController.class)) {
                violations.addAll(controllerViolations(type));
            }
            if (type.isRecord() && (type.getPackageName().contains(".presentation.response")
                    || type.getName().equals(ROOT + ".common.web.FieldErrorResponse"))) {
                checkResponseRecord(type, violations);
            }
        });
        assertThat(violations).isEmpty();
    }

    @Test
    void invalidDeclarationsAreRejected() {
        assertThat(controllerViolations(BadController.class))
            .anyMatch(error -> error.contains("nested HTTP record") && error.contains("Token"))
            .anyMatch(error -> error.contains("request"))
            .anyMatch(error -> error.contains("internal"));
        List<String> violations = new ArrayList<>();
        checkResponseRecord(BadResponse.class, violations);
        assertThat(violations).anyMatch(error -> error.contains("internal"));
    }

    private static List<String> controllerViolations(Class<?> controller) {
        List<String> violations = new ArrayList<>();
        for (Class<?> nested : controller.getDeclaredClasses()) {
            if (nested.isRecord() && (nested.getSimpleName().endsWith("Request")
                    || nested.getSimpleName().endsWith("Response"))) {
                violations.add("nested HTTP record: " + nested.getName());
            }
        }
        String domain = controller.getPackageName().replaceFirst(".*\\.([^.]+)\\.presentation.*", "$1");
        for (var method : controller.getDeclaredMethods()) {
            if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                continue;
            }
            checkResponse(method.getGenericReturnType(), violations);
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(RequestBody.class)) {
                    Class<?> type = parameter.getType();
                    if (!type.isRecord() || type.isMemberClass() || !type.getSimpleName().endsWith("Request")
                            || !type.getPackageName().equals(ROOT + "." + domain + ".presentation.request")) {
                        violations.add("invalid request: " + method);
                    }
                }
            }
        }
        return violations;
    }

    private static void checkResponse(Type type, List<String> violations) {
        if (type instanceof ParameterizedType parameterized) {
            checkResponse(parameterized.getRawType(), violations);
            for (Type argument : parameterized.getActualTypeArguments()) {
                checkResponse(argument, violations);
            }
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                checkResponse(bound, violations);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                checkResponse(bound, violations);
            }
        } else if (type instanceof Class<?> cls) {
            String name = cls.getPackageName();
            if (name.startsWith(ROOT + ".") && (name.contains(".domain") || name.contains(".api")
                    || name.contains(".application"))) {
                violations.add("internal response type: " + cls.getName());
            } else if (cls.isRecord() && cls.getDeclaringClass() != null
                    && cls.getDeclaringClass().isAnnotationPresent(RestController.class)) {
                violations.add("nested HTTP record: " + cls.getName());
            } else if (cls.isRecord() && name.contains(".presentation.response")) {
                checkResponseRecord(cls, violations);
            }
        }
    }

    private static void checkResponseRecord(Class<?> type, List<String> violations) {
        for (var component : type.getRecordComponents()) {
            checkResponse(component.getGenericType(), violations);
        }
    }

    @RestController
    static class BadController {
        record Registration(String value) {
        }

        record Token(String value) {
        }

        @GetMapping("/nested")
        Token nested() {
            return null;
        }

        @PostMapping("/bad")
        List<Member> bad(@RequestBody Registration request) {
            return List.of();
        }
    }

    record BadResponse(List<MemberRole> roles) {
    }
}
