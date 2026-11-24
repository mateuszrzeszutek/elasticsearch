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

import static org.junit.jupiter.api.Assertions.fail;

/** No annotation anywhere. The methods that are not tests by the convention fail if they are executed. */
public class OnlyNamedSampleTests extends ElasticsearchTest {

    public void testFirst() {}

    public void testSecond() {}

    void testPackagePrivate() {
        fail("package-private methods are not tests");
    }

    protected void testProtected() {
        fail("protected methods are not tests");
    }

    public static void testStatic() {
        fail("static methods are not tests");
    }

    public void testWithParameter(int ignored) {
        fail("methods with parameters are not tests");
    }

    public int testReturningValue() {
        fail("methods that return a value are not tests");
        return 0;
    }

    public void notATest() {
        fail("methods that are not named test* are not tests");
    }
}
