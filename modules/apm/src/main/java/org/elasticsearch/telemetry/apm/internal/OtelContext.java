/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.telemetry.apm.internal;

import io.opentelemetry.context.Context;

import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.telemetry.tracing.TraceContext;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Encapsulates getting and updating the OTel {@link Context} from the {@link ThreadContext}, where it's stored as
 * {@value Task#APM_TRACE_CONTEXT}.
 */
public final class OtelContext {

    private OtelContext() {}

    public static Context getOrDefault(TraceContext threadContext, Supplier<Context> defaultValue) {
        Context contextOrNull = threadContext.getTransient(Task.APM_TRACE_CONTEXT);
        return contextOrNull == null ? defaultValue.get() : contextOrNull;
    }

    public static Context updateAndGet(TraceContext threadContext, UnaryOperator<Context> update) {
        Context previousContextOrNull = threadContext.getTransient(Task.APM_TRACE_CONTEXT);
        Context updated = update.apply(previousContextOrNull == null ? Context.root() : previousContextOrNull);
        threadContext.putTransientAllowOverwrite(Task.APM_TRACE_CONTEXT, updated);
        return updated;
    }
}
