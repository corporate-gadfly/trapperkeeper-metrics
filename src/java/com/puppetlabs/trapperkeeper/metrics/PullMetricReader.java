package com.puppetlabs.trapperkeeper.metrics;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A pull-based {@link MetricReader} that collects metrics on demand.
 * <p>
 * Unlike push-based readers (e.g. {@code PeriodicMetricReader}), this reader
 * does not export metrics on a schedule. Instead, metrics are collected when
 * {@link #collectAllMetrics()} is called — typically in response to an HTTP
 * request to the {@code /metrics/v3} endpoint.
 * <p>
 * Uses {@link AggregationTemporality#CUMULATIVE} so that each scrape sees
 * monotonically increasing counters, matching Prometheus conventions.
 */
public class PullMetricReader implements MetricReader {

    private final AtomicReference<CollectionRegistration> registration = new AtomicReference<>();

    @Override
    public void register(CollectionRegistration registration) {
        if (!this.registration.compareAndSet(null, registration)) {
            throw new IllegalStateException("PullMetricReader already registered");
        }
    }

    /**
     * Collect all metrics from the SDK right now.
     *
     * @return an immutable collection of {@link MetricData}, or an empty
     *         collection if the reader has not been registered yet.
     */
    public Collection<MetricData> collectAllMetrics() {
        CollectionRegistration reg = this.registration.get();
        if (reg != null) {
            return reg.collectAllMetrics();
        }
        return Collections.emptyList();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        this.registration.set(null);
        return CompletableResultCode.ofSuccess();
    }
}

