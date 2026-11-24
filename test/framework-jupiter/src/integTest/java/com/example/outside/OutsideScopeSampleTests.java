/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package com.example.outside;

import org.elasticsearch.test.ElasticsearchTest;

import static org.junit.jupiter.api.Assertions.fail;

/** Extends the base class, but is in a package the agent does not look at. */
public class OutsideScopeSampleTests extends ElasticsearchTest {

    public void testNotRun() {
        fail("only classes in org.elasticsearch and co.elastic have their named methods run");
    }
}
