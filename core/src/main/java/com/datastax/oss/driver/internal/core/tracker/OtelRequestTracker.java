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

import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchableStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.QueryTrace;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.api.core.tracker.RequestTracker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public class OtelRequestTracker implements RequestTracker {

  private final Map<String, TracingInfo> logPrefixToSpanMap =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final Tracer tracer;

  public OtelRequestTracker(DriverContext context) {
    this.tracer =
        Objects.requireNonNull(context.getOpenTelemetry())
            .getTracer("com.datastax.oss.driver.internal.core.tracker.OtelRequestTracker");
  }

  @Override
  public void onRequestHandlerCreated(
      @NonNull DriverContext context, @NonNull String requestLogPrefix) {
    Span parentSpan = tracer.spanBuilder("Cassandra Java Driver").startSpan();
    parentSpan.addEvent("Request handler created");
    parentSpan.setAttribute("Session name", context.getSessionName());
    parentSpan.setAttribute("CqlRequestHandler hashcode", requestLogPrefix);
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
    Span parentSpan = logPrefixToSpanMap.get(requestLogPrefix).getParentSpan();
    parentSpan.setAttribute("Statement", statementToString(statement));
    Span createdSpan = logPrefixToSpanMap.get(requestLogPrefix).getCreatedSpan();
    createdSpan.end();
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
      @NonNull String requestLogPrefix) {
    RequestTracker.super.onNodeError(
        request, error, latencyNanos, executionProfile, node, requestLogPrefix);
    TracingInfo tracingInfo = logPrefixToSpanMap.get(requestLogPrefix);
    if (tracingInfo == null) {
      return;
    }
    Span span = tracingInfo.getParentSpan();
    span.recordException(error);
    span.end();
    span.setStatus(StatusCode.ERROR);
    logPrefixToSpanMap.remove(requestLogPrefix);
  }

  @Override
  public void onSuccess(
      @NonNull Request request,
      long latencyNanos,
      @NonNull DriverExecutionProfile executionProfile,
      @NonNull Node node,
      @NonNull String requestLogPrefix) {
    TracingInfo tracingInfo = logPrefixToSpanMap.get(requestLogPrefix);
    if (tracingInfo == null) {
      return;
    }
    Span span = tracingInfo.getParentSpan();
    span.end();
    span.setStatus(StatusCode.OK);
    logPrefixToSpanMap.remove(requestLogPrefix);
  }

  @Override
  public void onError(
      @NonNull Request request,
      @NonNull Throwable error,
      long latencyNanos,
      @NonNull DriverExecutionProfile executionProfile,
      @Nullable Node node,
      @NonNull String requestLogPrefix) {
    TracingInfo tracingInfo = logPrefixToSpanMap.get(requestLogPrefix);
    if (tracingInfo == null) {
      return;
    }
    Span span = tracingInfo.getParentSpan();
    span.recordException(error);
    span.end();
    span.setStatus(StatusCode.ERROR);
    logPrefixToSpanMap.remove(requestLogPrefix);
  }

  @Override
  public void onNodeSuccess(
      @NonNull Request request,
      long latencyNanos,
      @NonNull DriverExecutionProfile executionProfile,
      @NonNull Node node,
      @NonNull String requestLogPrefix,
      @NonNull AsyncResultSet resultSet) {
    RequestTracker.super.onNodeSuccess(
        request, latencyNanos, executionProfile, node, requestLogPrefix);
    TracingInfo tracingInfo = logPrefixToSpanMap.get(requestLogPrefix);
    if (tracingInfo == null) {
      return;
    }
    Span span = tracingInfo.getParentSpan();
    // add cassandra query trace
    if (resultSet.getExecutionInfo().getTracingId() != null) {
      new Thread(
              () -> {
                QueryTrace queryTrace = resultSet.getExecutionInfo().getQueryTrace();
                addCassandraQueryTraceToSpan(span, queryTrace);
                span.end();
              })
          .start();
    } else {
      span.end();
    }
    span.setStatus(StatusCode.OK);
    logPrefixToSpanMap.remove(requestLogPrefix);
  }

  private static String statementToString(Statement<?> statement) {
    if (statement instanceof BoundStatement) {
      return ((BoundStatement) statement).getPreparedStatement().toString();
    } else if (statement instanceof SimpleStatement) {
      return ((SimpleStatement) statement).getQuery();
    } else if (statement instanceof BatchStatement) {
      StringBuilder builder = new StringBuilder();
      BatchStatement batchStatement = (BatchStatement) statement;
      for (BatchableStatement<?> inner : batchStatement) {
        builder.append(statementToString(inner)).append(";\n");
      }
      return builder.toString();
    } else {
      // dead code
      return statement.toString();
    }
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

  private static class TracingInfo {
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
