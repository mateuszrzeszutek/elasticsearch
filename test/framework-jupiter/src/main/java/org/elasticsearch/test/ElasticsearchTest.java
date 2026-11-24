/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test;

/**
 * Base class of the Elasticsearch tests that run on JUnit Jupiter.
 * <p>
 * Extending it is what makes a test class eligible for the test method agent: in a class that extends this one, every
 * {@code public void testXxx()} method is run as a test without having to be annotated with {@code @Test}, and so is it in the
 * {@code @Nested} classes inside of it, which still need that annotation. Methods that are annotated are left alone, so both styles can
 * be mixed.
 * <p>
 * Keep this class minimal. Anything that should be shared by tests belongs in a test case that extends it, rather than here.
 */
public abstract class ElasticsearchTest {}
