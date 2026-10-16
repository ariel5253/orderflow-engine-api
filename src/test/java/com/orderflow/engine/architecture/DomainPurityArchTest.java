package com.orderflow.engine.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.anyElementThat;
import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.Currency;
import java.util.List;

@AnalyzeClasses(packages = "com.orderflow.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityArchTest {

    private static final String PRICING_DOMAIN = "com.orderflow.engine.pricing.domain..";

    private static final List<Class<?>> FLOATING_POINT_TYPES =
            List.of(float.class, double.class, Float.class, Double.class);

    private static final DescribedPredicate<JavaClass> FLOATING_POINT = describe(
            "float, double, Float or Double (or an array of them)",
            type -> FLOATING_POINT_TYPES.stream().anyMatch(type.getBaseComponentType()::isEquivalentTo));

    private static final DescribedPredicate<JavaAccess<?>> FLOATING_POINT_ACCESS = describe(
            "a target that belongs to, takes or returns float, double, Float or Double",
            access -> FLOATING_POINT.test(access.getTargetOwner())
                    || (access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                            && (target.getRawParameterTypes().stream().anyMatch(FLOATING_POINT)
                                    || FLOATING_POINT.test(target.getRawReturnType()))));

    @ArchTest
    static final ArchRule pricingDomainDependsOnTheJdkOnly = classes()
            .that().resideInAPackage(PRICING_DOMAIN)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", PRICING_DOMAIN)
            .because("the pricing domain is pure and depends on the JDK only");

    @ArchTest
    static final ArchRule pricingDomainUsesNoJdbc = noClasses()
            .that().resideInAPackage(PRICING_DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
            .because("the pricing domain never reaches the database, even through the JDK");

    @ArchTest
    static final ArchRule pricingDomainUsesNoFloatingPoint = CompositeArchRule
            .of(noFields().that().areDeclaredInClassesThat().resideInAPackage(PRICING_DOMAIN)
                    .should().haveRawType(FLOATING_POINT))
            .and(noCodeUnits().that().areDeclaredInClassesThat().resideInAPackage(PRICING_DOMAIN)
                    .should().haveRawParameterTypes(anyElementThat(FLOATING_POINT)))
            .and(noMethods().that().areDeclaredInClassesThat().resideInAPackage(PRICING_DOMAIN)
                    .should().haveRawReturnType(FLOATING_POINT))
            .and(noClasses().that().resideInAPackage(PRICING_DOMAIN)
                    .should().accessTargetWhere(FLOATING_POINT_ACCESS))
            .and(noClasses().that().resideInAPackage(PRICING_DOMAIN)
                    .should().dependOnClassesThat(FLOATING_POINT))
            .as("no field, parameter, return type, call, method or constructor reference, or"
                    + " dependency of the pricing domain uses float, double, Float or Double")
            .because("money is never a binary floating-point number (AC-08)");

    @ArchTest
    static final ArchRule pricingDomainNeverReadsTheJdkCurrencyTable = noClasses()
            .that().resideInAPackage(PRICING_DOMAIN)
            .should().dependOnClassesThat().belongToAnyOf(Currency.class)
            .because("scale and rounding come from country data, and java.util.Currency reports COP"
                    + " with scale 2 (RF-5)");
}
