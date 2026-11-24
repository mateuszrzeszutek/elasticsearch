/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.junit.jupiter.patch;

import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.platform.commons.support.AnnotationSupport;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The name-based half of test method discovery in the patched JUnit Jupiter engine.
 * <p>
 * Stock Jupiter only treats a method as a test if it is annotated with {@code @Test}. The patched engine produced by this module
 * additionally accepts methods that follow the JUnit 3 / RandomizedRunner convention, so that {@code public void testSomething()} keeps
 * working without an annotation. This class is compiled as part of this module and merged into the patched engine jar; the patcher
 * rewrites {@code IsTestMethod#test(Method)} to call {@link #isPublicNonStaticTestNamedMethod(Method)} when the annotation check fails.
 */
public final class TestNamedMethods {

    private static final String TEST_PREFIX = "test";

    private TestNamedMethods() {}

    /**
     * Whether the given method is a test by naming convention: {@code public}, non-static, non-abstract, {@code void}, taking no
     * parameters and with a name starting with {@code test}. These are the same rules as the {@code JUnit3MethodProvider} of the
     * randomized runner.
     * <p>
     * Methods that Jupiter would resolve as something other than a plain test (annotated with {@code @TestFactory}, {@code @TestTemplate}
     * or a composed annotation such as {@code @RepeatedTest}) are rejected, otherwise they would be resolved twice. Methods that are
     * annotated with {@code @Test} are accepted by the stock check before this one is ever consulted.
     */
    public static boolean isPublicNonStaticTestNamedMethod(Method method) {
        int modifiers = method.getModifiers();
        return Modifier.isPublic(modifiers)
            && Modifier.isStatic(modifiers) == false
            && Modifier.isAbstract(modifiers) == false
            && method.isSynthetic() == false
            && method.getReturnType() == void.class
            && method.getParameterCount() == 0
            && method.getName().startsWith(TEST_PREFIX)
            && AnnotationSupport.isAnnotated(method, TestFactory.class) == false
            && AnnotationSupport.isAnnotated(method, TestTemplate.class) == false;
    }
}
