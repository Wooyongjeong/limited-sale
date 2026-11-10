package com.wooyongjeong.limitedsale.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureTest {
    private static final String[] DOMAINS = {"member", "product", "sale", "order", "payment"};

    private static boolean inPackage(String actual, String expected) {
        return actual.equals(expected) || actual.startsWith(expected + ".");
    }

    private static ArchRule boundary(String root, String from, String to) {
        String target = root + "." + to;
        return noClasses().that().resideInAPackage(root + "." + from + "..")
            .should().dependOnClassesThat(new DescribedPredicate<>("reside in another domain outside its API") {
                @Override
                public boolean test(JavaClass type) {
                    return inPackage(type.getPackageName(), target)
                        && !inPackage(type.getPackageName(), target + ".api");
                }
            }).allowEmptyShould(true);
    }

    private static ArchRule apiRule(String root, String domain) {
        return noClasses().that().resideInAPackage(root + "." + domain + ".api..")
            .should().dependOnClassesThat(new DescribedPredicate<>("reside in a domain outside this API") {
                @Override
                public boolean test(JavaClass type) {
                    String actual = type.getPackageName();
                    for (String other : DOMAINS) {
                        if (inPackage(actual, root + "." + other)
                                && !inPackage(actual, root + "." + domain + ".api")) {
                            return true;
                        }
                    }
                    return false;
                }
            }).allowEmptyShould(true);
    }

    private static ArchRule pureDomains() {
        return noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..presentation..", "..infrastructure..", "jakarta.persistence..", "jakarta.servlet..",
                "org.hibernate..", "org.springframework..", "org.apache.kafka..",
                "io.lettuce..", "redis.clients..", "org.redisson..")
            .allowEmptyShould(true);
    }

    private static ArchRule cycles(String root) {
        return slices().matching(root + ".(*)..").should().beFreeOfCycles().allowEmptyShould(true);
    }

    @Test
    void productionBoundaries() {
        String root = "com.wooyongjeong.limitedsale";
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(root);
        for (String domain : DOMAINS) {
            apiRule(root, domain).check(classes);
            for (String other : DOMAINS) {
                if (!domain.equals(other)) boundary(root, domain, other).check(classes);
            }
        }
        pureDomains().check(classes);
        cycles(root).check(classes);
    }

    @Test
    void violationsAreDetected() {
        ClassFileImporter importer = new ClassFileImporter();
        JavaClasses internalReference = importer.importClasses(
            fixtures.order.application.BadOrder.class, fixtures.member.domain.PrivateMember.class);
        JavaClasses falseApi = importer.importClasses(
            fixtures.order.application.BadApiInternalReference.class, fixtures.member.apiinternal.InternalType.class);
        JavaClasses badApi = importer.importClasses(fixtures.member.api.BadApi.class, fixtures.member.domain.PrivateMember.class);
        JavaClasses domainImplementation = importer.importClasses(
            fixtures.domainbad.domain.BadDomain.class, fixtures.domainbad.infrastructure.Store.class);
        JavaClasses domainFramework = importer.importClasses(fixtures.violation.domain.FrameworkDependency.class);
        JavaClasses cycle = importer.importClasses(fixtures.cycle.order.Order.class, fixtures.cycle.sale.Sale.class);

        assertThatThrownBy(() -> boundary("fixtures", "order", "member").check(internalReference)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> boundary("fixtures", "order", "member").check(falseApi)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> apiRule("fixtures", "member").check(badApi)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> pureDomains().check(domainImplementation)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> pureDomains().check(domainFramework)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> cycles("fixtures.cycle").check(cycle)).isInstanceOf(AssertionError.class);
    }
}
