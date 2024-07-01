/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.tracker;

import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.QueryTrace;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.api.core.tracker.RequestTracker;
import com.datastax.oss.driver.internal.core.context.DefaultDriverContext;
import com.datastax.oss.driver.internal.core.metadata.DefaultEndPoint;
import com.datastax.oss.driver.internal.core.metadata.SniEndPoint;
import com.datastax.oss.driver.shaded.guava.common.base.Splitter;
import com.datastax.oss.driver.shaded.guava.common.collect.Iterables;
import com.datastax.oss.driver.shaded.guava.common.util.concurrent.ThreadFactoryBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.semconv.ServerAttributes;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: 1. retry 2. speculative execution 3. semantic conventions Concerns: 1. It relies on log
 * prefix to identify each request 2. `ConcurrentHashMap` may lead to memory leak if a request
 * somehow neither succeed nor fail 3. What should be the default of ExecutorService? 4.
 * `logPrefixToSpanMap` is not thread-safe and is it fine?
 *
 * <p>Specify the following in your {@code application.conf} to enable OpenTelemetry for tracing:
 *
 * <pre>
 * datastax-java-driver.advanced.request-tracker {
 *   class = OtelRequestTracker
 * }
 * </pre>
 *
 * <p>You have to pass in an OpenTelemetry instance to use OtelRequestTracker. You can optionally
 * pass in an ExecutorService instance to use for fetching Cassandra native query trace.
 *
 * <p>Some code snippets are taken from the <a
 * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/cassandra/cassandra-4.4/library/src/main/java/io/opentelemetry/instrumentation/cassandra/v4_4/CassandraAttributesExtractor.java">OpenTelemetry
 * Java Instrumentation project</a>
 */
public class OtelRequestTracker implements RequestTracker {

  private final Map<String, TracingInfo> logPrefixToSpanMap =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final Tracer tracer;
  private final ExecutorService threadPool;

  private final DefaultDriverContext context;

  private final RequestLogFormatter formatter;

  private final Logger LOG = LoggerFactory.getLogger(OtelRequestTracker.class);

  private final Field proxyAddressField = getProxyAddressField();

  private static final AttributeKey<String> DB_CASSANDRA_CONSISTENCY_LEVEL =
      AttributeKey.stringKey("db.cassandra.consistency_level");
  private static final AttributeKey<String> DB_CASSANDRA_COORDINATOR_DC =
      AttributeKey.stringKey("db.cassandra.coordinator.dc");
  private static final AttributeKey<String> DB_CASSANDRA_COORDINATOR_ID =
      AttributeKey.stringKey("db.cassandra.coordinator.id");
  private static final AttributeKey<Boolean> DB_CASSANDRA_IDEMPOTENCE =
      AttributeKey.booleanKey("db.cassandra.idempotence");
  private static final AttributeKey<Long> DB_CASSANDRA_PAGE_SIZE =
      AttributeKey.longKey("db.cassandra.page_size");
  private static final AttributeKey<Long> DB_CASSANDRA_SPECULATIVE_EXECUTION_COUNT =
      AttributeKey.longKey("db.cassandra.speculative_execution_count");

  public OtelRequestTracker(DriverContext context) {
    this.context = (DefaultDriverContext) context;
    OpenTelemetry openTelemetry = this.context.getOpenTelemetry();
    if (openTelemetry == null) {
      throw new IllegalStateException(
          "You have to pass in an OpenTelemetry instance to use OtelRequestTracker");
    }
    this.tracer =
        Objects.requireNonNull(this.context.getOpenTelemetry())
            .getTracer("com.datastax.oss.driver.internal.core.tracker.OtelRequestTracker");
    this.threadPool =
        this.context.getOpenTelemetryNativeTraceExecutor() != null
            ? this.context.getOpenTelemetryNativeTraceExecutor()
            : new ThreadPoolExecutor(
                1,
                Math.max(Runtime.getRuntime().availableProcessors(), 1),
                10,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000),
                new ThreadFactoryBuilder().setNameFormat("otel-thread-%d").build(),
                new ThreadPoolExecutor.AbortPolicy());
    this.formatter = this.context.getRequestLogFormatter();
  }

  @Override
  public void onRequestHandlerCreated(
      @NonNull DriverContext context, @NonNull String requestLogPrefix) {
    Span parentSpan = tracer.spanBuilder("Cassandra Java Driver").startSpan();
    parentSpan.addEvent("Request handler created");
    parentSpan.setAttribute("Session name", context.getSessionName());
    parentSpan.setAttribute(
        "CqlRequestHandler hashcode", Iterables.get(Splitter.on('|').split(requestLogPrefix), 1));
    Span createdSpan =
        tracer
            .spanBuilder("Driver Processing Request")
            .setParent(Context.current().with(parentSpan))
            .startSpan();
    TracingInfo tracingInfo = new TracingInfo();
    tracingInfo.setParentSpan(parentSpan);
    tracingInfo.setCreatedSpan(createdSpan);
    logPrefixToSpanMap.put(requestLogPrefix, tracingInfo);
  }

  @Override
  public void onRequestSent(
      @NonNull Statement<?> statement, @NonNull Node node, @NonNull String requestLogPrefix) {
    logPrefixToSpanMap.computeIfPresent(
        requestLogPrefix,
        (k, tracingInfo) -> {
          Span parentSpan = tracingInfo.getParentSpan();
          parentSpan.setAttribute("Statement", statementToString(statement));
          Span createdSpan = tracingInfo.getCreatedSpan();
          createdSpan.end();
          return tracingInfo;
        });
  }

  @Override
  public void close() throws Exception {
    logPrefixToSpanMap.clear();
  }

  @Override
  public void onNodeError(
      @NonNull Request request,
      @NonNull Throwable error,
      long latencyNanos,
      @NonNull DriverExecutionProfile executionProfile,
      @NonNull Node node,
      @NonNull String requestLogPrefix,
      @Nullable ExecutionInfo executionInfo) {
    logPrefixToSpanMap.computeIfPresent(
        requestLogPrefix,
        (k, v) -> {
          Span span = v.getParentSpan();
          span.recordException(error);
          span.setStatus(StatusCode.ERROR);
          onEnd(span, request, executionInfo);
          return null;
        });
  }

  @Override
  public void onSuccess(
      @NonNull Request request,
      long latencyNanos,
      @NonNull DriverExecutionProfile executionProfile,
      @NonNull Node node,
      @NonNull String requestLogPrefix,
      @Nullable ExecutionInfo executionInfo) {
    logPrefixToSpanMap.computeIfPresent(
        requestLogPrefix,
        (k, v) -> {
          Span span = v.getParentSpan();
          span.setStatus(StatusCode.OK);
          onEnd(span, request, executionInfo);
          return null;
        });
  }

  @Override
  public void onError(
      @NonNull Request request,
      @NonNull Throwable error,
      long latencyNanos,
      @NonNull DriverExecutionProfile executionProfile,
      @Nullable Node node,
      @NonNull String requestLogPrefix,
      @Nullable ExecutionInfo executionInfo) {
    logPrefixToSpanMap.computeIfPresent(
        requestLogPrefix,
        (k, v) -> {
          Span span = v.getParentSpan();
          span.recordException(error);
          span.setStatus(StatusCode.ERROR);
          onEnd(span, request, executionInfo);
          return null;
        });
  }

  @Override
  public void onNodeSuccess(
      @NonNull Request request,
      long latencyNanos,
      @NonNull DriverExecutionProfile executionProfile,
      @NonNull Node node,
      @NonNull String requestLogPrefix,
      @NonNull ExecutionInfo executionInfo) {
    logPrefixToSpanMap.computeIfPresent(
        requestLogPrefix,
        (k, v) -> {
          Span span = v.getParentSpan();
          span.setStatus(StatusCode.OK);
          onEnd(span, request, executionInfo);
          return null;
        });
  }

  private void onEnd(
      @NonNull Span span, @NonNull Request request, @Nullable ExecutionInfo executionInfo) {
    AttributesBuilder builder = gatherAttributes((Statement<?>) request, executionInfo);
    builder.build().forEach((k, v) -> span.setAttribute(k.getKey(), v.toString()));
    // add cassandra query trace
    if (executionInfo != null && executionInfo.getTracingId() != null) {
      threadPool.submit(
          () -> {
            QueryTrace queryTrace = executionInfo.getQueryTrace();
            addCassandraQueryTraceToSpan(span, queryTrace);
            span.end();
          });
    } else {
      span.end();
    }
  }

  private String statementToString(Request request) {
    StringBuilder builder = new StringBuilder();
    this.formatter.appendQueryString(
        request, RequestLogger.DEFAULT_REQUEST_LOGGER_MAX_QUERY_LENGTH, builder);
    this.formatter.appendValues(
        request,
        RequestLogger.DEFAULT_REQUEST_LOGGER_MAX_VALUES,
        RequestLogger.DEFAULT_REQUEST_LOGGER_MAX_VALUE_LENGTH,
        true,
        builder);
    return builder.toString();
  }

  private void addCassandraQueryTraceToSpan(Span parentSpan, QueryTrace queryTrace) {
    Span span =
        this.tracer
            .spanBuilder("Cassandra Internal")
            .setStartTimestamp(Instant.ofEpochMilli(queryTrace.getStartedAt()))
            .setParent(Context.current().with(parentSpan))
            .startSpan();
    queryTrace
        .getEvents()
        .forEach(
            event -> {
              span.addEvent(
                  Objects.requireNonNull(event.getActivity()),
                  Instant.ofEpochMilli(event.getTimestamp()));
            });

    span.end(
        Instant.ofEpochMilli(queryTrace.getStartedAt() + queryTrace.getDurationMicros() / 1000));
  }

  private AttributesBuilder gatherAttributes(
      @NonNull Statement<?> statement, @Nullable ExecutionInfo executionInfo) {
    AttributesBuilder builder = Attributes.builder();

    if (executionInfo != null && executionInfo.getCoordinator() != null) {
      Node coordinator = executionInfo.getCoordinator();
      updateServerAddressAndPort(builder, coordinator);

      if (coordinator.getDatacenter() != null) {
        builder.put(DB_CASSANDRA_COORDINATOR_DC, coordinator.getDatacenter());
      }
      if (coordinator.getHostId() != null) {
        builder.put(DB_CASSANDRA_COORDINATOR_ID, coordinator.getHostId().toString());
      }
    }

    String consistencyLevel;
    DriverExecutionProfile config = this.context.getConfig().getDefaultProfile();
    if (statement.getConsistencyLevel() != null) {
      consistencyLevel = statement.getConsistencyLevel().name();
    } else {
      consistencyLevel = config.getString(DefaultDriverOption.REQUEST_CONSISTENCY);
    }
    builder.put(DB_CASSANDRA_CONSISTENCY_LEVEL, consistencyLevel);
    if (executionInfo != null) {
      builder.put(
          DB_CASSANDRA_SPECULATIVE_EXECUTION_COUNT, executionInfo.getSpeculativeExecutionCount());
    }

    if (statement.getPageSize() > 0) {
      builder.put(DB_CASSANDRA_PAGE_SIZE, statement.getPageSize());
    } else {
      int pageSize = config.getInt(DefaultDriverOption.REQUEST_PAGE_SIZE);
      if (pageSize > 0) {
        builder.put(DB_CASSANDRA_PAGE_SIZE, pageSize);
      }
    }

    Boolean idempotent = statement.isIdempotent();
    if (idempotent == null) {
      idempotent = config.getBoolean(DefaultDriverOption.REQUEST_DEFAULT_IDEMPOTENCE);
    }
    builder.put(DB_CASSANDRA_IDEMPOTENCE, idempotent);
    return builder;
  }

  private void updateServerAddressAndPort(AttributesBuilder attributes, Node coordinator) {
    EndPoint endPoint = coordinator.getEndPoint();
    if (endPoint instanceof DefaultEndPoint) {
      InetSocketAddress address = ((DefaultEndPoint) endPoint).resolve();
      attributes.put(ServerAttributes.SERVER_ADDRESS, address.getHostString());
      attributes.put(ServerAttributes.SERVER_PORT, address.getPort());
    } else if (endPoint instanceof SniEndPoint && proxyAddressField != null) {
      SniEndPoint sniEndPoint = (SniEndPoint) endPoint;
      Object object = null;
      try {
        object = proxyAddressField.get(sniEndPoint);
      } catch (Exception e) {
        this.LOG.trace(
            "Error when accessing the private field proxyAddress of SniEndPoint using reflection.");
      }
      if (object instanceof InetSocketAddress) {
        InetSocketAddress address = (InetSocketAddress) object;
        attributes.put(ServerAttributes.SERVER_ADDRESS, address.getHostString());
        attributes.put(ServerAttributes.SERVER_PORT, address.getPort());
      }
    }
  }

  @Nullable
  private Field getProxyAddressField() {
    try {
      Field field = SniEndPoint.class.getDeclaredField("proxyAddress");
      field.setAccessible(true);
      return field;
    } catch (Exception e) {
      return null;
    }
  }

  private static class TracingInfo {
    // each request should be in the same thread
    private Span parentSpan;
    private Span createdSpan; // the span from handler created to request sent

    private void setParentSpan(Span parentSpan) {
      this.parentSpan = parentSpan;
    }

    private Span getParentSpan() {
      return parentSpan;
    }

    private void setCreatedSpan(Span createdSpan) {
      this.createdSpan = createdSpan;
    }

    private Span getCreatedSpan() {
      return createdSpan;
    }
  }
}
