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
package com.datastax.oss.driver.internal.core.session;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.QueryTrace;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metrics.Metrics;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public class OtelSession implements CqlSession {

  private final CqlSession delegate;
  private final Tracer tracer;

  public OtelSession(CqlSession delegate) {
    this.delegate = delegate;
    this.tracer =
        Objects.requireNonNull(delegate.getContext().getOpenTelemetry())
            .getTracer("Driver Cql Request");
  }

  @NonNull
  @Override
  public CompletionStage<Void> closeFuture() {
    return delegate.closeFuture();
  }

  @NonNull
  @Override
  public CompletionStage<Void> closeAsync() {
    return delegate.closeAsync();
  }

  @NonNull
  @Override
  public CompletionStage<Void> forceCloseAsync() {
    return delegate.forceCloseAsync();
  }

  @NonNull
  @Override
  public String getName() {
    return delegate.getName();
  }

  @NonNull
  @Override
  public Metadata getMetadata() {
    return delegate.getMetadata();
  }

  @Override
  public boolean isSchemaMetadataEnabled() {
    return delegate.isSchemaMetadataEnabled();
  }

  @NonNull
  @Override
  public CompletionStage<Metadata> setSchemaMetadataEnabled(@Nullable Boolean newValue) {
    return delegate.setSchemaMetadataEnabled(newValue);
  }

  @NonNull
  @Override
  public CompletionStage<Metadata> refreshSchemaAsync() {
    return delegate.refreshSchemaAsync();
  }

  @NonNull
  @Override
  public CompletionStage<Boolean> checkSchemaAgreementAsync() {
    return delegate.checkSchemaAgreementAsync();
  }

  @NonNull
  @Override
  public DriverContext getContext() {
    return delegate.getContext();
  }

  @NonNull
  @Override
  public Optional<CqlIdentifier> getKeyspace() {
    return delegate.getKeyspace();
  }

  @NonNull
  @Override
  public Optional<Metrics> getMetrics() {
    return delegate.getMetrics();
  }

  @Nullable
  @Override
  public <RequestT extends Request, ResultT> ResultT execute(
      @NonNull RequestT request, @NonNull GenericType<ResultT> resultType) {
    return null;
  }

  @Override
  public ResultSet execute(Statement<?> statement) {
    Statement<?> injected = statement.setTracing(true);
    ResultSet rs = delegate.execute(injected);
    Span parent = Span.current();
    try (Scope parentScope = parent.makeCurrent()) {
      if (injected.isTracing()) {
        ExecutionInfo info = rs.getExecutionInfo();
        QueryTrace queryTrace = info.getQueryTrace();
        // send queryTrace to Otel
        Span span =
            tracer
                .spanBuilder("Cassandra Internal")
                .setStartTimestamp(Instant.ofEpochMilli(queryTrace.getStartedAt()))
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
          queryTrace
              .getEvents()
              .forEach(
                  event -> {
                    span.addEvent(
                        Objects.requireNonNull(event.getActivity()),
                        Instant.ofEpochMilli(event.getTimestamp()));
                  });
        } finally {
          span.end(
              Instant.ofEpochMilli(
                  queryTrace.getStartedAt() + queryTrace.getDurationMicros() / 1000));
        }
      }
    } finally {
      parent.end();
    }
    return rs;
  }
}
