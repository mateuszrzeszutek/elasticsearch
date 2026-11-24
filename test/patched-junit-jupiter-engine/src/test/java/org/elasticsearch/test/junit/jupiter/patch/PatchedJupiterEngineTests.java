/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.junit.jupiter.patch;

import com.carrotsearch.randomizedtesting.JUnit3MethodProvider;
import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.annotations.TestMethodProviders;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.engine.discovery.predicates.IsTestMethod;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.lang.reflect.Method;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * Checks what the patched Jupiter engine discovers in fixture classes. The fixtures are only discovered through the launcher and never
 * executed by it; their bodies are empty because the test task excludes them, see build.gradle.
 */
@TestMethodProviders({ JUnit3MethodProvider.class })
public class PatchedJupiterEngineTests extends RandomizedTest {

    /** Every flavour of method named {@code test*}, only the public, non-static, void, no-argument ones are tests. */
    public static class NamedMethodsFixture {
        public void testPublic() {}

        public void testAnotherPublic() {}

        void testPackagePrivate() {}

        protected void testProtected() {}

        @SuppressWarnings("unused")
        private void testPrivate() {}

        public static void testStatic() {}

        public void testWithParameter(int ignored) {}

        public int testReturningValue() {
            return 0;
        }

        public void notATest() {}

        public void thisIsNotTestEither() {}

        public void tes() {}
    }

    /** Stock behavior, a method is a test when it is annotated, whatever it is called. */
    public static class AnnotatedMethodsFixture {
        @Test
        void annotatedPackagePrivate() {}

        @Test
        public void annotatedPublic() {}

        /** Named and annotated, this must not be discovered twice. */
        @Test
        public void testNamedAndAnnotated() {}
    }

    /** Methods that are tests of another kind must not also be discovered as plain tests. */
    public static class OtherTestKindsFixture {
        @RepeatedTest(2)
        public void testRepeated() {}

        @TestFactory
        public Stream<?> testFactory() {
            return Stream.empty();
        }
    }

    public abstract static class AbstractFixture {
        public abstract void testAbstract();

        public void testConcreteInAbstractClass() {}
    }

    public static class ConcreteFixture extends AbstractFixture {
        @Override
        public void testAbstract() {}

        public void testOwn() {}
    }

    /** Has no method that is a test by either rule, so it must not be recognised as a test class. */
    public static class NoTestsFixture {
        public void helper() {}

        public void tes() {}
    }

    private static final String ENGINE_ID = "es-jupiter-engine";

    public void testEngineIsRegisteredUnderItsOwnId() {
        Set<String> ids = new TreeSet<>();
        for (TestEngine engine : ServiceLoader.load(TestEngine.class)) {
            ids.add(engine.getId());
        }
        assertThat(ids.contains(ENGINE_ID), equalTo(true));
        assertThat(ids.contains("junit-jupiter"), equalTo(false));
    }

    public void testDiscoveredTestsBelongToTheRenamedEngine() {
        TestPlan plan = discover(NamedMethodsFixture.class);
        assertThat(
            plan.getRoots().stream().map(root -> root.getUniqueIdObject().getEngineId().orElseThrow()).toList(),
            contains(ENGINE_ID)
        );
    }

    public void testNamedMethodsAreDiscoveredWithoutAnnotation() {
        assertThat(discovered(NamedMethodsFixture.class), containsInAnyOrder("testPublic", "testAnotherPublic"));
    }

    public void testAnnotatedMethodsAreStillDiscovered() {
        assertThat(
            discovered(AnnotatedMethodsFixture.class),
            containsInAnyOrder("annotatedPackagePrivate", "annotatedPublic", "testNamedAndAnnotated")
        );
    }

    public void testMethodsThatAreBothNamedAndAnnotatedAreDiscoveredOnce() {
        assertThat(discoveredCount(AnnotatedMethodsFixture.class, "testNamedAndAnnotated"), equalTo(1L));
    }

    public void testOtherKindsOfTestsAreNotDiscoveredAsPlainTests() {
        TestPlan plan = discover(OtherTestKindsFixture.class);
        // the repeated test is a template, the factory a factory; neither of them has been resolved a second time as a plain test
        assertThat(discoveredCount(plan, "testRepeated"), equalTo(0L));
        assertThat(discoveredCount(plan, "testFactory"), equalTo(0L));
        assertThat(containers(plan, "testRepeated"), equalTo(1L));
        assertThat(containers(plan, "testFactory"), equalTo(1L));
    }

    public void testAbstractMethodsAreNotDiscoveredButInheritedOnesAre() {
        assertThat(discovered(AbstractFixture.class), empty());
        assertThat(discovered(ConcreteFixture.class), containsInAnyOrder("testAbstract", "testConcreteInAbstractClass", "testOwn"));
    }

    public void testClassWithoutTestsIsNotATestClass() {
        assertThat(discovered(NoTestsFixture.class), empty());
    }

    public void testSelectingAMethodByNameFindsIt() throws Exception {
        TestPlan plan = LauncherFactory.create()
            .discover(
                LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectMethod(NamedMethodsFixture.class, "testPublic"))
                    .filters(EngineFilter.includeEngines(ENGINE_ID))
                    .build()
            );
        assertThat(methodNames(plan), containsInAnyOrder("testPublic"));
    }

    public void testSelectingAMethodThatIsNotATestFindsNothing() throws Exception {
        TestPlan plan = LauncherFactory.create()
            .discover(
                LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectMethod(NamedMethodsFixture.class, "testPackagePrivate"))
                    .filters(EngineFilter.includeEngines(ENGINE_ID))
                    .build()
            );
        assertThat(methodNames(plan), empty());
    }

    /** The jar has to hold the patched engine, not the stock one, otherwise none of the above means anything. */
    public void testPatchedClassCallsTheHelper() throws Exception {
        try (var in = IsTestMethod.class.getResourceAsStream("IsTestMethod.class")) {
            String constantPool = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertThat(constantPool.contains("isPublicNonStaticTestNamedMethod"), equalTo(true));
        }
    }

    // --- the naming rule itself

    public void testNamingRuleRequiresPublicNonStaticVoidNoArgumentMethodStartingWithTest() throws Exception {
        assertThat(rule(NamedMethodsFixture.class, "testPublic"), equalTo(true));
        assertThat(rule(NamedMethodsFixture.class, "testPackagePrivate"), equalTo(false));
        assertThat(rule(NamedMethodsFixture.class, "testProtected"), equalTo(false));
        assertThat(rule(NamedMethodsFixture.class, "testStatic"), equalTo(false));
        assertThat(rule(NamedMethodsFixture.class, "testWithParameter", int.class), equalTo(false));
        assertThat(rule(NamedMethodsFixture.class, "testReturningValue"), equalTo(false));
        assertThat(rule(NamedMethodsFixture.class, "notATest"), equalTo(false));
        assertThat(rule(AbstractFixture.class, "testAbstract"), equalTo(false));
        assertThat(rule(OtherTestKindsFixture.class, "testRepeated"), equalTo(false));
        assertThat(rule(OtherTestKindsFixture.class, "testFactory"), equalTo(false));
    }

    // --- helpers

    private static boolean rule(Class<?> clazz, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = ReflectionSupport.findMethod(clazz, name, parameters).orElseThrow(() -> new NoSuchMethodException(name));
        return TestNamedMethods.isPublicNonStaticTestNamedMethod(method);
    }

    private static TestPlan discover(Class<?> fixture) {
        return LauncherFactory.create()
            .discover(
                LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(fixture))
                    .filters(EngineFilter.includeEngines(ENGINE_ID))
                    .build()
            );
    }

    private static Set<String> discovered(Class<?> fixture) {
        return methodNames(discover(fixture));
    }

    private static Set<String> methodNames(TestPlan plan) {
        Set<String> names = new TreeSet<>();
        for (TestIdentifier id : leaves(plan)) {
            names.add(((MethodSource) id.getSource().orElseThrow()).getMethodName());
        }
        return names;
    }

    private static long discoveredCount(Class<?> fixture, String method) {
        return discoveredCount(discover(fixture), method);
    }

    private static long discoveredCount(TestPlan plan, String method) {
        return leaves(plan).stream()
            .filter(id -> id.getSource().orElseThrow() instanceof MethodSource source && source.getMethodName().equals(method))
            .count();
    }

    /** Containers (templates, factories) that come from the given method. */
    private static long containers(TestPlan plan, String method) {
        return plan.getRoots()
            .stream()
            .flatMap(root -> plan.getDescendants(root).stream())
            .filter(id -> id.getType() == TestDescriptor.Type.CONTAINER)
            .filter(id -> id.getSource().orElse(null) instanceof MethodSource source && source.getMethodName().equals(method))
            .count();
    }

    private static Set<TestIdentifier> leaves(TestPlan plan) {
        Set<TestIdentifier> tests = new java.util.LinkedHashSet<>();
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier descendant : plan.getDescendants(root)) {
                if (descendant.isTest()) {
                    tests.add(descendant);
                }
            }
        }
        return tests;
    }
}
