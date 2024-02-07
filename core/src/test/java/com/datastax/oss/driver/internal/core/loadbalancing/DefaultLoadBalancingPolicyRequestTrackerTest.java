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
package com.datastax.oss.driver.internal.core.loadbalancing;

import static com.datastax.oss.driver.Assertions.assertThat;
import static com.datastax.oss.driver.api.core.config.DriverExecutionProfile.DEFAULT_NAME;
import static org.mockito.BDDMockito.given;

import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class DefaultLoadBalancingPolicyRequestTrackerTest extends LoadBalancingPolicyTestBase {

  @Mock Request request;
  @Mock DriverExecutionProfile profile;
  final String logPrefix = "lbp-test-log-prefix";

  private DefaultLoadBalancingPolicy policy;
  private long nextNanoTime;

  @Before
  @Override
  public void setup() {
    super.setup();
    given(metadataManager.getContactPoints()).willReturn(ImmutableSet.of(node1));
    policy =
        new DefaultLoadBalancingPolicy(context, DEFAULT_NAME) {
          @Override
          protected long nanoTime() {
            return nextNanoTime;
          }
        };
    policy.init(
        ImmutableMap.of(
            UUID.randomUUID(), node1,
            UUID.randomUUID(), node2,
            UUID.randomUUID(), node3),
        distanceReporter);
  }

  @Test
  public void should_return_the_latency_if_it_does_not_change() {

    // When
    for (int i = 0; i < 100; i++) {
      policy.onNodeSuccess(request, TimeUnit.MILLISECONDS.toNanos(100), profile, node1, logPrefix);
    }

    // Then
    assertThat(policy.latencies)
        .hasEntrySatisfying(
            node1,
            tracker ->
                assertThat(tracker.getCurrentAverage().average)
                    .isEqualTo(TimeUnit.MILLISECONDS.toNanos(100)));
  }

  @Test
  public void should_less_than_highest_latency() {

    // When
    for (int i = 0; i < 100; i++) {
      policy.onNodeSuccess(request, TimeUnit.MILLISECONDS.toNanos(100), profile, node1, logPrefix);
    }

    policy.onNodeError(request, null, TimeUnit.MILLISECONDS.toNanos(50), profile, node1, logPrefix);

    // Then
    assertThat(policy.latencies)
        .hasEntrySatisfying(
            node1,
            tracker ->
                assertThat(tracker.getCurrentAverage().average)
                    .isLessThan(TimeUnit.MILLISECONDS.toNanos(100)));
  }
}
