package com.wooyongjeong.mockpg.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Parameter;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureTest {
    @Test
    void domainIsIndependentAndApplicationsDoNotShareCode() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.wooyongjeong.mockpg");
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                        "..infrastructure..", "..presentation..").check(classes);
        noClasses().should().dependOnClassesThat().resideInAPackage("com.wooyongjeong.limitedsale..").check(classes);
        noClasses().that().resideInAPackage("..presentation..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..").check(classes);
    }

    @Test
    void responseRecordsDoNotExposeInternalTypes() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.wooyongjeong.mockpg.presentation.response");
        classes.stream().map(type -> type.reflect()).filter(Class::isRecord)
                .flatMap(type -> java.util.Arrays.stream(type.getRecordComponents()))
                .forEach(component -> assertThat(component.getType().getPackageName())
                        .doesNotStartWith("com.wooyongjeong.mockpg.domain")
                        .doesNotStartWith("com.wooyongjeong.mockpg.infrastructure"));
    }

    @Test
    void requestBodiesUseTopLevelPresentationRecords() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.wooyongjeong.mockpg.presentation");
        classes.stream().map(type -> type.reflect()).filter(type -> type.isAnnotationPresent(RestController.class))
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods()))
                .flatMap(method -> java.util.Arrays.stream(method.getParameters()))
                .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
                .map(Parameter::getType).forEach(type -> {
                    assertThat(type.isRecord()).isTrue();
                    assertThat(type.getDeclaringClass()).isNull();
                    assertThat(type.getPackageName()).isEqualTo("com.wooyongjeong.mockpg.presentation.request");
                    assertThat(type.getSimpleName()).endsWith("Request");
                });
    }
}
