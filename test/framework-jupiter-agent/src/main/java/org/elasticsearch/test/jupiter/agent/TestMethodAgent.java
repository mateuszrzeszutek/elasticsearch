/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.jupiter.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * A {@code -javaagent} that adds {@code @Test} to the {@code public void testXxx()} methods of the classes that extend
 * {@code org.elasticsearch.test.ElasticsearchTest}, and of the {@code @Nested} classes inside of them, at class load time, so that
 * Jupiter discovers them without the annotation.
 * <p>
 * The agent is only offered the classes of the {@link #PACKAGES packages} that hold Elasticsearch code. Set the system property
 * {@code es.jupiter.agent.stats} to report what the agent did when the JVM exits: to the file that the property names, or to the
 * standard error if it is {@code true}.
 */
public final class TestMethodAgent {

    static final String BASE_CLASS = "org/elasticsearch/test/ElasticsearchTest";
    static final List<String> PACKAGES = List.of("org/elasticsearch/", "co/elastic/");
    /** The agent is not applied to itself. */
    static final String AGENT_PACKAGE = "org/elasticsearch/test/jupiter/agent/";

    private TestMethodAgent() {}

    @SuppressForbidden(reason = "the agent has no logging, and the standard error is where the build shows what the test JVM prints")
    private static void reportStats(String stats, String destination) {
        if (destination.equals("true")) {
            System.err.println(stats);
            return;
        }
        try {
            Files.writeString(Path.of(destination), stats + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[es-jupiter-agent] could not write the stats to " + destination + ": " + e);
        }
    }

    @SuppressForbidden(reason = "the agent has no logging, and the standard error is where the build shows what the test JVM prints")
    public static void premain(String arguments, Instrumentation instrumentation) {
        TestMethodTransformer transformer = new TestMethodTransformer(BASE_CLASS, PACKAGES, List.of(AGENT_PACKAGE));
        // A transformer is offered every class that is loaded, including the ones it needs for itself. Loading those from the
        // transformer would re-enter it, so they are loaded now, before it is registered.
        transformer.warmUp();
        instrumentation.addTransformer(transformer);
        String stats = System.getProperty("es.jupiter.agent.stats");
        if (stats != null && stats.isEmpty() == false) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> reportStats(transformer.stats(), stats), "es-jupiter-agent-stats"));
        }
    }
}
