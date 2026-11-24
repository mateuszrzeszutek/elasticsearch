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

/** Not a test itself, being abstract, but its named methods are inherited by the tests that extend it. */
public abstract class AbstractNamedSampleTests extends ElasticsearchTest {

    public void testInheritedFromAbstractBase() {}

    public abstract void testImplementedBySubclass();
}
