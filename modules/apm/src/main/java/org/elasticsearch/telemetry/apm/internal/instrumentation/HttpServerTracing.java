/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.telemetry.apm.internal.instrumentation;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.config.IncludeExclude;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.SpanStatusExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesGetter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpSpanNameExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpSpanStatusExtractor;

import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.telemetry.apm.internal.tracing.APMSpan;
import org.elasticsearch.telemetry.apm.internal.tracing.APMSpanBuilder;
import org.elasticsearch.telemetry.apm.internal.tracing.APMTracer;
import org.elasticsearch.telemetry.instrumentation.HttpServerInstrumentation;
import org.elasticsearch.telemetry.tracing.SpanBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class HttpServerTracing implements HttpServerInstrumentation {

    private final ThreadContext threadContext;
    private final APMTracer tracer;

    private final HttpServerAttributesGetter<RequestAndRoute, RestResponse> getter;
    private final SpanNameExtractor<RequestAndRoute> spanNameExtractor;
    private final AttributesExtractor<RequestAndRoute, RestResponse> httpServerAttributesExtractor;
    private final SpanStatusExtractor<RequestAndRoute, RestResponse> httpSpanStatusExtractor;

    public HttpServerTracing(ThreadContext threadContext, APMTracer tracer) {
        this.threadContext = threadContext;
        this.tracer = tracer;

        this.getter = new OtelAttributesGetter();
        this.spanNameExtractor = HttpSpanNameExtractor.create(getter);
        this.httpServerAttributesExtractor = HttpServerAttributesExtractor.builder(getter)
            .setRequestHeaders(IncludeExclude.builder()/* TODO which headers? */.build())
            .setResponseHeaders(IncludeExclude.builder()/* TODO which headers? */.build())
            .build();
        this.httpSpanStatusExtractor = HttpSpanStatusExtractor.create(getter);
    }

    @Override
    public ThreadContext.StoredContext start(RestRequest request, @Nullable String matchedRoute) {
        var req = new RequestAndRoute(request, matchedRoute);
        // The trace context of the client is the remote parent of the span started below. It is only stored in the request headers
        // (and thus propagated to other nodes) for as long as this node does not replace it with the context of its own span.
        // TODO this will be replaced by standard OpenTelemetry TextMapPropagator later on
        copyRemoteTraceContext(request, Task.TRACE_PARENT_HTTP_HEADER);
        copyRemoteTraceContext(request, Task.TRACE_STATE);

        final APMSpanBuilder spanBuilder = tracer.spanBuilder(spanNameExtractor.extract(req)).setSpanKind(SpanKind.SERVER);
        setLegacyRequestAttributes(spanBuilder, req);
        httpServerAttributesExtractor.onStart(
            spanBuilder.attributesBuilder(),
            /* we don't care about the context in this case */ Context.root(),
            req
        );

        return spanBuilder.startSpan().storeInThreadContext();
    }

    private void copyRemoteTraceContext(RestRequest request, String header) {
        final List<String> values = request.getHeaders().get(header);
        if (values != null && values.isEmpty() == false) {
            threadContext.putHeaderAllowOverwrite(header, values.getFirst());
        }
    }

    private void setLegacyRequestAttributes(SpanBuilder spanBuilder, RequestAndRoute req) {
        req.request().getHeaders().forEach((key, values) -> {
            final String lowerKey = key.toLowerCase(Locale.ROOT).replace('-', '_');
            spanBuilder.setAttribute("http.request.headers." + lowerKey, values == null ? "" : String.join("; ", values));
        });
        spanBuilder.setAttribute("http.method", Objects.requireNonNullElse(getter.getHttpRequestMethod(req), "<unknown>"));
        spanBuilder.setAttribute("http.url", Objects.requireNonNullElse(req.request().uri(), "<unknown>"));
        spanBuilder.setAttribute("http.flavour", getter.getNetworkProtocolVersion(req, null));
    }

    @Override
    public void recordException(RestRequest request, Throwable t) {
        tracer.currentSpan().recordException(t);
    }

    @Override
    public Releasable prepareEnd(RestRequest request, RestResponse response) {
        // the span of the request is the current one when its response is sent, see start(); spans that are not recorded are not current
        if (tracer.currentSpan() instanceof APMSpan span) {
            return () -> {
                setLegacyResponseAttributes(span, response);

                var requestAndRoute = new RequestAndRoute(request, /* only needed at start */ null);
                var attributes = Attributes.builder();
                httpServerAttributesExtractor.onEnd(
                    attributes,
                    /* we don't care about the context in this case */ Context.root(),
                    requestAndRoute,
                    response,
                    null
                );
                span.setAllAttributes(attributes.build());

                httpSpanStatusExtractor.extract(span, requestAndRoute, response, null);
                span.end();
            };
        }
        return () -> {};
    }

    private void setLegacyResponseAttributes(APMSpan span, RestResponse response) {
        span.setAttribute("http.status_code", response.status().getStatus());
        response.getHeaders().forEach((key, values) -> span.setAttribute("http.response.headers." + key, String.join("; ", values)));
    }
}
