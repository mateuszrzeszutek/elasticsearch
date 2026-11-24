/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.jupiter.samples;

/** Two levels below the base class: the second of them is found by following the chain of superclasses. */
public class InheritingSampleTests extends AbstractNamedSampleTests {

    @Override
    public void testImplementedBySubclass() {}

    public void testOwn() {}
}
