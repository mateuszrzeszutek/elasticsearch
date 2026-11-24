/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.jupiter.samples;

import org.elasticsearch.test.ElasticsearchTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Jupiter features around methods that are found by their name. */
public class LifecycleSampleTests extends ElasticsearchTest {

    private boolean setUp;

    @BeforeEach
    void setUp() {
        setUp = true;
    }

    @AfterEach
    void tearDown() {
        setUp = false;
    }

    public void testBeforeEachRanBeforeIt() {
        assertTrue(setUp, "@BeforeEach must apply to methods found by name");
    }

    @Disabled("checks that @Disabled applies to methods found by name")
    public void testDisabled() {
        fail("a disabled method must not run");
    }

    /** Nested classes are part of the test they are in: the callbacks of the enclosing class apply to them. */
    @Nested
    class NestedSample {

        public void testInNestedClass() {
            assertTrue(setUp, "the @BeforeEach of the enclosing class applies to a nested class");
        }

        void testPackagePrivate() {
            fail("package-private methods are not tests, in nested classes either");
        }

        @Nested
        class DeeplyNestedSample {

            public void testInDeeplyNestedClass() {
                assertTrue(setUp, "the @BeforeEach of the outermost class applies at every level");
            }
        }
    }

    /** An inner class without the annotation is not a nested test for Jupiter, so its methods are not tests. */
    class InnerWithoutNestedSample {

        public void testNotRun() {
            fail("only the inner classes that are annotated as @Nested are tests");
        }
    }
}
