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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public class OtelRequestTracker implements RequestTracker {

  private Map<String, Span> logPrefixToSpanMap;

  private Map<String, Tracer> logPrefixToTracer;

  public void onRequestHandlerCreated(
      @NonNull DriverContext context, @NonNull String requestLogPrefix) {
    Tracer tracer =
        Objects.requireNonNull(context.getOpenTelemetry())
            .getTracer("com.datastax.oss.driver.internal.core.tracker.OtelRequestTracker");
    logPrefixToTracer.put(requestLogPrefix, tracer);
    Span span = tracer.spanBuilder("Driver Internal Tracing").startSpan();
    span.addEvent("Request handler created");
    span.setAttribute("Session name", context.getSessionName());
    span.setAttribute("CqlRequestHandler hashcode", requestLogPrefix);
    logPrefixToSpanMap.put(requestLogPrefix, span);
  }

  public void onRequestSent(
      @NonNull Statement<?> statement, @NonNull Node node, @NonNull String requestLogPrefix) {
    Span span = logPrefixToSpanMap.get(requestLogPrefix);
    span.addEvent("Request sent");
    span.setAttribute("Statement", statementToString(statement));
  }

  @Override
  public void close() throws Exception {
    logPrefixToTracer.clear();
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
    Span span = logPrefixToSpanMap.get(requestLogPrefix);
    span.recordException(error);
    span.end();
    logPrefixToSpanMap.remove(requestLogPrefix);
    logPrefixToTracer.remove(requestLogPrefix);
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
    Span span = logPrefixToSpanMap.get(requestLogPrefix);
    span.addEvent("Request success");
    // add cassandra query trace
    // TODO: this must not be called on a driver thread
    if (resultSet.getExecutionInfo().getTracingId() != null) {
      QueryTrace queryTrace = resultSet.getExecutionInfo().getQueryTrace();
      addCassandraQueryTraceToSpan(span, requestLogPrefix, queryTrace);
    }
    span.end();
    logPrefixToSpanMap.remove(requestLogPrefix);
    logPrefixToTracer.remove(requestLogPrefix);
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

  private void addCassandraQueryTraceToSpan(
      Span parentSpan, String requestLogPrefix, QueryTrace queryTrace) {
    Span span =
        logPrefixToTracer
            .get(requestLogPrefix)
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
}
