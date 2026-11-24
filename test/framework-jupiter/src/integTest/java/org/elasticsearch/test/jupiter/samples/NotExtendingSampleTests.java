/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.jupiter.samples;

import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.fail;

/** In the right package, but does not extend the base class: its named methods are none of the agent's business. */
public class NotExtendingSampleTests {

    public void testNotRun() {
        fail("only classes that extend ElasticsearchTest have their named methods run");
    }

    /** Nested in something that is not a test of the base class, so not a test of it either. */
    @Nested
    class NestedInUnrelatedSample {

        public void testNotRun() {
            fail("only nested classes of tests that extend ElasticsearchTest have their named methods run");
        }
    }
}
