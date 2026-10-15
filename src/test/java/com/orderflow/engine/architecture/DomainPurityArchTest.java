package com.orderflow.engine.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.orderflow.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityArchTest {

    private static final String PRICING_DOMAIN = "com.orderflow.engine.pricing.domain..";

    @ArchTest
    static final ArchRule pricingDomainDependsOnTheJdkOnly = classes()
            .that().resideInAPackage(PRICING_DOMAIN)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", PRICING_DOMAIN)
            .because("the pricing domain is pure and depends on the JDK only")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule pricingDomainUsesNoJdbc = noClasses()
            .that().resideInAPackage(PRICING_DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
            .because("the pricing domain never reaches the database, even through the JDK")
            .allowEmptyShould(true);
}
