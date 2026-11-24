/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.jupiter.agent;

import com.carrotsearch.randomizedtesting.JUnit3MethodProvider;
import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.annotations.TestMethodProviders;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests what the transformer does to the class files of fixture classes. Nothing is run: the fixtures are read, transformed, and then
 * looked at, and one is loaded to see that the result is a valid class.
 */
@TestMethodProviders({ JUnit3MethodProvider.class })
public class TestMethodTransformerTests extends RandomizedTest {

    private static final String TEST = "Lorg/junit/jupiter/api/Test;";

    /** Stands in for {@code ElasticsearchTest}. */
    public abstract static class BaseFixture {}

    /** Every kind of method that is called {@code test*}: only the public, not static ones without parameters or result are tests. */
    public static class NamedFixture extends BaseFixture {
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

        public void tes() {}
    }

    /** Methods that are annotated already: the ones that are tests of some kind must be left as they are. */
    public static class AnnotatedFixture extends BaseFixture {
        @Test
        public void testAlreadyATest() {}

        @RepeatedTest(2)
        public void testRepeated() {}

        @TestTemplate
        public void testTemplate() {}

        @Disabled("a disabled test is still a test, and the annotation has to stay")
        public void testDisabled() {}

        @Test
        public void annotatedWithoutPrefix() {}
    }

    public abstract static class MiddleFixture extends BaseFixture {
        public void testInMiddle() {}
    }

    public static class LeafFixture extends MiddleFixture {
        public void testInLeaf() {}
    }

    /** A test that has nested tests, at two levels, and an inner class that is not one of them for it lacks the annotation. */
    public static class OuterFixture extends BaseFixture {
        public void testOuter() {}

        @Nested
        public class NestedFixture {
            public void testInNested() {}

            void testPackagePrivate() {}

            @Nested
            public class DeeperFixture {
                public void testInDeeper() {}
            }
        }

        public class InnerWithoutNestedFixture {
            public void testNotNested() {}
        }
    }

    /** Nested, but in a class that is not a test of the base class. */
    public static class UnrelatedOuterFixture {
        @Nested
        public class NestedInUnrelatedFixture {
            public void testNothing() {}
        }
    }

    /** Has test methods, but is not a test of the base class. */
    public static class UnrelatedFixture {
        public void testNothing() {}
    }

    public interface InterfaceFixture {
        default void testDefault() {}
    }

    /** Defines a class from bytes, with the loader of the test as its parent so that the fixture base class is found. */
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static final String BASE = internalName(BaseFixture.class);

    private final TestMethodTransformer transformer = new TestMethodTransformer(BASE, List.of("org/elasticsearch/"), List.of());

    public void testNamedMethodsAreAnnotated() throws IOException {
        assertThat(annotated(transformer, NamedFixture.class), containsInAnyOrder("testPublic", "testAnotherPublic"));
    }

    public void testExistingAnnotationsAreRespected() throws IOException {
        ClassNode node = transformed(transformer, AnnotatedFixture.class);
        // only the disabled one is a plain test that is not annotated as one
        assertThat(annotatedMethods(node), containsInAnyOrder("testAlreadyATest", "testDisabled", "annotatedWithoutPrefix"));
        assertThat(count(method(node, "testAlreadyATest"), TEST), equalTo(1L));
        assertThat(count(method(node, "annotatedWithoutPrefix"), TEST), equalTo(1L));
        assertThat(count(method(node, "testRepeated"), TEST), equalTo(0L));
        assertThat(count(method(node, "testTemplate"), TEST), equalTo(0L));
    }

    public void testOtherAnnotationsAreKept() throws IOException {
        MethodNode disabled = method(transformed(transformer, AnnotatedFixture.class), "testDisabled");
        List<String> descriptors = disabled.visibleAnnotations.stream().map(a -> a.desc).toList();
        assertThat(descriptors, containsInAnyOrder("Lorg/junit/jupiter/api/Disabled;", TEST));
    }

    public void testTheWholeChainOfSuperclassesIsFollowed() throws IOException {
        assertThat(annotated(transformer, LeafFixture.class), containsInAnyOrder("testInLeaf"));
        assertThat(annotated(transformer, MiddleFixture.class), containsInAnyOrder("testInMiddle"));
    }

    public void testNestedClassesAreFollowedToTheTestTheyAreIn() throws IOException {
        assertThat(annotated(transformer, OuterFixture.class), containsInAnyOrder("testOuter"));
        assertThat(annotated(transformer, OuterFixture.NestedFixture.class), containsInAnyOrder("testInNested"));
        assertThat(annotated(transformer, OuterFixture.NestedFixture.DeeperFixture.class), containsInAnyOrder("testInDeeper"));
    }

    public void testInnerClassesThatAreNotAnnotatedAsNestedAreLeftAlone() throws IOException {
        assertThat(transform(transformer, OuterFixture.InnerWithoutNestedFixture.class), nullValue());
    }

    public void testNestedClassesInClassesThatAreNotTestsAreLeftAlone() throws IOException {
        assertThat(transform(transformer, UnrelatedOuterFixture.NestedInUnrelatedFixture.class), nullValue());
    }

    public void testNestedClassesWhoseEnclosingClassIsOutsideOfThePackagesAreLeftAlone() throws IOException {
        TestMethodTransformer narrow = new TestMethodTransformer(BASE, List.of(internalName(OuterFixture.NestedFixture.class)), List.of());
        assertThat(transform(narrow, OuterFixture.NestedFixture.class), nullValue());
    }

    public void testClassesThatDoNotExtendTheBaseAreLeftAlone() throws IOException {
        assertThat(transform(transformer, UnrelatedFixture.class), nullValue());
        assertThat(transform(transformer, BaseFixture.class), nullValue());
    }

    public void testInterfacesAreLeftAlone() throws IOException {
        assertThat(transform(transformer, InterfaceFixture.class), nullValue());
    }

    public void testClassesOutsideOfThePackagesAreLeftAlone() throws IOException {
        TestMethodTransformer elsewhere = new TestMethodTransformer(BASE, List.of("co/elastic/"), List.of());
        assertThat(transform(elsewhere, NamedFixture.class), nullValue());
    }

    public void testExcludedPackagesAreLeftAlone() throws IOException {
        TestMethodTransformer excluded = new TestMethodTransformer(
            BASE,
            List.of("org/elasticsearch/"),
            List.of("org/elasticsearch/test/jupiter/agent/")
        );
        assertThat(transform(excluded, NamedFixture.class), nullValue());
    }

    public void testAClassWhoseAncestorIsOutsideOfThePackagesIsLeftAlone() throws IOException {
        // the base class is outside of the scope, which is how it is for a class that extends a library class
        TestMethodTransformer narrow = new TestMethodTransformer(BASE, List.of(internalName(LeafFixture.class)), List.of());
        assertThat(transform(narrow, LeafFixture.class), nullValue());
    }

    public void testClassesBeingRedefinedAndBootstrapClassesAreLeftAlone() throws IOException {
        byte[] bytes = bytesOf(NamedFixture.class);
        String name = internalName(NamedFixture.class);
        assertThat(transformer.transform(null, name, null, null, bytes), nullValue());
        assertThat(transformer.transform(getClass().getClassLoader(), name, NamedFixture.class, null, bytes), nullValue());
    }

    public void testTheResultIsAValidClassWithTheAnnotations() throws Exception {
        byte[] bytes = transform(transformer, NamedFixture.class);
        Class<?> transformedClass = new DefiningLoader(getClass().getClassLoader()).define(NamedFixture.class.getName(), bytes);
        // creating an instance makes the JVM verify the class
        transformedClass.getConstructor().newInstance();
        assertThat(transformedClass.getMethod("testPublic").isAnnotationPresent(Test.class), equalTo(true));
        assertThat(transformedClass.getMethod("testStatic").isAnnotationPresent(Test.class), equalTo(false));
        assertThat(transformedClass.getMethod("notATest").isAnnotationPresent(Test.class), equalTo(false));
    }

    public void testTransformingAgainGivesTheSameResult() throws IOException {
        Set<String> first = annotated(transformer, LeafFixture.class);
        Set<String> second = annotated(transformer, LeafFixture.class);
        assertThat(second, equalTo(first));
        assertThat(second, hasItem("testInLeaf"));
    }

    public void testWarmUpDoesNotFail() {
        new TestMethodTransformer(BASE, List.of("org/elasticsearch/"), List.of()).warmUp();
    }

    public void testStatsCountWhatWasDone() throws IOException {
        TestMethodTransformer counting = new TestMethodTransformer(BASE, List.of("org/elasticsearch/"), List.of());
        transform(counting, NamedFixture.class);
        transform(counting, UnrelatedFixture.class);
        assertThat(counting.stats().contains("transformed=1"), equalTo(true));
        assertThat(counting.stats().contains("annotated-methods=2"), equalTo(true));
    }

    // helpers: none of them starts with "test", which would make the method provider take it for a test

    private static String internalName(Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }

    private static byte[] bytesOf(Class<?> clazz) throws IOException {
        try (InputStream in = clazz.getResourceAsStream("/" + internalName(clazz) + ".class")) {
            return in.readAllBytes();
        }
    }

    private byte[] transform(TestMethodTransformer subject, Class<?> clazz) throws IOException {
        return subject.transform(getClass().getClassLoader(), internalName(clazz), null, null, bytesOf(clazz));
    }

    private ClassNode transformed(TestMethodTransformer subject, Class<?> clazz) throws IOException {
        byte[] bytes = transform(subject, clazz);
        assertThat(clazz.getSimpleName() + " was not transformed", bytes == null, equalTo(false));
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private Set<String> annotated(TestMethodTransformer subject, Class<?> clazz) throws IOException {
        return annotatedMethods(transformed(subject, clazz));
    }

    private static Set<String> annotatedMethods(ClassNode node) {
        Set<String> names = new TreeSet<>();
        for (MethodNode method : node.methods) {
            if (count(method, TEST) > 0) {
                names.add(method.name);
            }
        }
        return names;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static long count(MethodNode method, String descriptor) {
        return method.visibleAnnotations == null ? 0 : method.visibleAnnotations.stream().filter(a -> a.desc.equals(descriptor)).count();
    }
}
