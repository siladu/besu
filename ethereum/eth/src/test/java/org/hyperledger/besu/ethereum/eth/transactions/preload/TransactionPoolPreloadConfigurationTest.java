/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.transactions.preload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

public class TransactionPoolPreloadConfigurationTest {

  @Test
  public void shouldCreateDisabledConfiguration() {
    final TransactionPoolPreloadConfiguration config =
        TransactionPoolPreloadConfiguration.disabled();

    assertThat(config.isEnabled()).isFalse();
    assertThat(config.getBatchSize())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_BATCH_SIZE);
    assertThat(config.getPreloadInterval())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_PRELOAD_INTERVAL);
  }

  @Test
  public void shouldCreateDefaultConfiguration() {
    final TransactionPoolPreloadConfiguration config =
        TransactionPoolPreloadConfiguration.builder().build();

    assertThat(config.isEnabled()).isFalse();
    assertThat(config.getBatchSize())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_BATCH_SIZE);
    assertThat(config.getPreloadInterval())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_PRELOAD_INTERVAL);
    assertThat(config.getImmediatePreloadCount())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_IMMEDIATE_PRELOAD_COUNT);
    assertThat(config.getWorkerThreads())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_WORKER_THREADS);
    assertThat(config.getMaxPreloadsPerSecond())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_MAX_PRELOADS_PER_SECOND);
    assertThat(config.getMaxQueueDepth())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_MAX_QUEUE_DEPTH);
    assertThat(config.getDeduplicationWindow())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_DEDUPLICATION_WINDOW);
    assertThat(config.isCircuitBreakerEnabled()).isTrue();
    assertThat(config.getCircuitBreakerThreshold())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_CIRCUIT_BREAKER_THRESHOLD);
  }

  @Test
  public void shouldCreateCustomConfiguration() {
    final TransactionPoolPreloadConfiguration config =
        TransactionPoolPreloadConfiguration.builder()
            .enabled(true)
            .batchSize(100)
            .preloadInterval(Duration.ofSeconds(2))
            .immediatePreloadCount(30)
            .workerThreads(4)
            .maxPreloadsPerSecond(1000)
            .maxQueueDepth(20000)
            .deduplicationWindow(Duration.ofSeconds(20))
            .circuitBreakerEnabled(false)
            .circuitBreakerThreshold(10000)
            .build();

    assertThat(config.isEnabled()).isTrue();
    assertThat(config.getBatchSize()).isEqualTo(100);
    assertThat(config.getPreloadInterval()).isEqualTo(Duration.ofSeconds(2));
    assertThat(config.getImmediatePreloadCount()).isEqualTo(30);
    assertThat(config.getWorkerThreads()).isEqualTo(4);
    assertThat(config.getMaxPreloadsPerSecond()).isEqualTo(1000);
    assertThat(config.getMaxQueueDepth()).isEqualTo(20000);
    assertThat(config.getDeduplicationWindow()).isEqualTo(Duration.ofSeconds(20));
    assertThat(config.isCircuitBreakerEnabled()).isFalse();
    assertThat(config.getCircuitBreakerThreshold()).isEqualTo(10000);
  }

  @Test
  public void shouldUseDefaultValuesForUnsetParameters() {
    final TransactionPoolPreloadConfiguration config =
        TransactionPoolPreloadConfiguration.builder().enabled(true).batchSize(75).build();

    assertThat(config.isEnabled()).isTrue();
    assertThat(config.getBatchSize()).isEqualTo(75);
    assertThat(config.getPreloadInterval())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_PRELOAD_INTERVAL);
    assertThat(config.getWorkerThreads())
        .isEqualTo(TransactionPoolPreloadConfiguration.DEFAULT_WORKER_THREADS);
  }

  @Test
  public void shouldBuildMultipleIndependentConfigurations() {
    final TransactionPoolPreloadConfiguration config1 =
        TransactionPoolPreloadConfiguration.builder().enabled(true).batchSize(50).build();

    final TransactionPoolPreloadConfiguration config2 =
        TransactionPoolPreloadConfiguration.builder().enabled(false).batchSize(100).build();

    assertThat(config1.isEnabled()).isTrue();
    assertThat(config1.getBatchSize()).isEqualTo(50);
    assertThat(config2.isEnabled()).isFalse();
    assertThat(config2.getBatchSize()).isEqualTo(100);
  }
}
