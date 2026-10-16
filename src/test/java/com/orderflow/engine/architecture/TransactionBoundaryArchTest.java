package com.orderflow.engine.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.orderflow.engine.pricing.domain.CartNormalizer;
import com.orderflow.engine.pricing.domain.PricingEngine;
import com.orderflow.engine.quoting.SnapshotProvider;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/**
 * AC-47, P-05 and SPEC sections 6, 7.6 and 12: a transaction starts only on an application use case, never in a
 * controller or an adapter; every call to a transactional method crosses the use case's proxy, because a call from
 * the same class would silently run without a transaction; a transactional use case never reaches push, the ERP or
 * any HTTP client; use cases depend on ports, never on adapters or wiring; and the confirmation never prices a cart
 * again, so the confirmed total is the quoted total by construction.
 */
@AnalyzeClasses(packages = "com.orderflow.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class TransactionBoundaryArchTest {

    private static final String[] APPLICATION = {
        "com.orderflow.engine.quoting..", "com.orderflow.engine.ordering..", "com.orderflow.engine.outbox.."};

    private static final List<String> TRANSACTIONAL_ANNOTATIONS = List.of(
            "org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional");

    private static final DescribedPredicate<CanBeAnnotated> TRANSACTIONAL = describe(
            "annotated with @Transactional",
            element -> TRANSACTIONAL_ANNOTATIONS.stream().anyMatch(element::isMetaAnnotatedWith));

    /** A method annotated itself, or a public method of a class annotated as a whole. */
    private static final DescribedPredicate<JavaMethod> STARTS_A_TRANSACTION = describe(
            "start a transaction",
            method -> TRANSACTIONAL.test(method)
                    || (TRANSACTIONAL.test(method.getOwner()) && method.getModifiers().contains(JavaModifier.PUBLIC)));

    private static final DescribedPredicate<JavaClass> HAVE_A_TRANSACTIONAL_METHOD = describe(
            "have a method that starts a transaction",
            type -> type.getMethods().stream().anyMatch(STARTS_A_TRANSACTION));

    private static final DescribedPredicate<JavaClass> APPLICATION_USE_CASE =
            resideInAnyPackage(APPLICATION).and(simpleNameEndingWith("UseCase"))
                    .as("application use cases (classes named *UseCase in quoting, ordering or outbox)");

    /** HTTP clients and the gateways of the external systems the outbox relay alone may call (SPEC section 7.4). */
    private static final DescribedPredicate<JavaClass> REMOTE_CALLS = resideInAnyPackage(
            "java.net.http..", "org.springframework.web.client..", "org.springframework.web.reactive.function.client..",
            "com.orderflow.engine.adapters.out.push..", "com.orderflow.engine.adapters.out.erp..")
            .or(simpleName("PushNotificationPort"))
            .or(simpleName("ErpPublisherPort"))
            .as("an HTTP client, or the push or ERP gateway");

    private static final ArchCondition<JavaMethod> BE_CALLED_ONLY_FROM_OTHER_CLASSES = new ArchCondition<>(
            "be called only from other classes") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
            method.getAccessesToSelf().stream()
                    .filter(access -> access.getOriginOwner().equals(method.getOwner()))
                    .forEach(access -> events.add(SimpleConditionEvent.violated(method, access.getDescription())));
        }
    };

    @ArchTest
    static final ArchRule onlyUseCasesAnnotateMethodsAsTransactional = methods()
            .that(TRANSACTIONAL)
            .should().beDeclaredInClassesThat(APPLICATION_USE_CASE)
            .because("the transaction boundary is the use case: a controller that opened it would answer before the"
                    + " commit, and an adapter that opened it would split one confirmation into several (I-201)");

    @ArchTest
    static final ArchRule onlyUseCasesAreTransactionalAsAWhole = classes()
            .that(TRANSACTIONAL)
            .should(ArchCondition.from(APPLICATION_USE_CASE))
            .allowEmptyShould(true)
            .because("the transaction boundary is the use case (SPEC section 12)");

    @ArchTest
    static final ArchRule transactionalMethodsAreCalledThroughTheProxy = methods()
            .that(STARTS_A_TRANSACTION)
            .should(BE_CALLED_ONLY_FROM_OTHER_CLASSES)
            .because("a call from the same class bypasses the transaction proxy, so it would run with no transaction"
                    + " (SPEC section 7.6)");

    @ArchTest
    static final ArchRule noRemoteCallInsideATransaction = noClasses()
            .that(HAVE_A_TRANSACTIONAL_METHOD)
            .should().dependOnClassesThat(REMOTE_CALLS)
            .because("a remote call inside a transaction holds its locks for someone else's latency, and an effect"
                    + " sent before the commit may describe an order that never existed (P-04, P-05)");

    @ArchTest
    static final ArchRule useCasesDependOnPortsNeverOnAdapters = noClasses()
            .that().resideInAnyPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.orderflow.engine.adapters..", "com.orderflow.engine.config..")
            .because("dependencies point inward: only the configuration knows both the ports and their adapters");

    @ArchTest
    static final ArchRule theConfirmationNeverPricesAgain = noClasses()
            .that().resideInAPackage("com.orderflow.engine.ordering..")
            .should().dependOnClassesThat().belongToAnyOf(PricingEngine.class, CartNormalizer.class)
            .orShould().callMethodWhere(target(name("pricingSnapshot"))
                    .and(target(owner(JavaClass.Predicates.assignableTo(SnapshotProvider.class)))))
            .because("an order is rendered from its sealed quote and never priced again, so a price, lever or engine"
                    + " change cannot reach it (SPEC section 6)");
}
