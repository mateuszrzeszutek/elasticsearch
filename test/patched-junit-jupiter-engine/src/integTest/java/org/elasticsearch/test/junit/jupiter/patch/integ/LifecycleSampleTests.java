/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.junit.jupiter.patch.integ;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Jupiter features around methods that are discovered by their name. */
public class LifecycleSampleTests {

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

    @Nested
    class NestedSample {

        public void testInNestedClass() {
            assertTrue(setUp, "the @BeforeEach of the enclosing class applies to a nested class");
        }
    }
}
