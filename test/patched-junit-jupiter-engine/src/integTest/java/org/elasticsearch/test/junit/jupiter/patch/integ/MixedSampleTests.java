/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.junit.jupiter.patch.integ;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/** Annotated and named methods side by side. */
public class MixedSampleTests {

    @Test
    void annotatedWithoutPrefix() {}

    /** Matches both rules, and must still run exactly once. */
    @Test
    public void testNamedAndAnnotated() {}

    public void testNamedOnly() {}

    void helperThatIsNotATest() {
        fail("helpers are not tests");
    }
}
