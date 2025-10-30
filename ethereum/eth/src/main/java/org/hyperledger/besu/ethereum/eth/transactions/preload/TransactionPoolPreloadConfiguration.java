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

import java.time.Duration;

/**
 * Configuration for transaction pool preloading feature. This feature proactively preloads account
 * state and storage from transactions in the prioritized layer of the transaction pool to warm
 * Bonsai merkle trie caches before block execution.
 */
public class TransactionPoolPreloadConfiguration {

  /** Default batch size for periodic preloading */
  public static final int DEFAULT_BATCH_SIZE = 50;

  /** Default interval for periodic preload queue refresh */
  public static final Duration DEFAULT_PRELOAD_INTERVAL = Duration.ofSeconds(1);

  /** Default number of top transactions to preload immediately */
  public static final int DEFAULT_IMMEDIATE_PRELOAD_COUNT = 20;

  /** Default number of worker threads for preload operations */
  public static final int DEFAULT_WORKER_THREADS = 2;

  /** Default maximum preload operations per second */
  public static final int DEFAULT_MAX_PRELOADS_PER_SECOND = 500;

  /** Default maximum queue depth before circuit breaker engages */
  public static final int DEFAULT_MAX_QUEUE_DEPTH = 10_000;

  /** Default deduplication window */
  public static final Duration DEFAULT_DEDUPLICATION_WINDOW = Duration.ofSeconds(10);

  /** Default circuit breaker threshold */
  public static final int DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 5_000;

  private final boolean enabled;
  private final int batchSize;
  private final Duration preloadInterval;
  private final int immediatePreloadCount;
  private final int workerThreads;
  private final int maxPreloadsPerSecond;
  private final int maxQueueDepth;
  private final Duration deduplicationWindow;
  private final boolean circuitBreakerEnabled;
  private final int circuitBreakerThreshold;

  private TransactionPoolPreloadConfiguration(
      final boolean enabled,
      final int batchSize,
      final Duration preloadInterval,
      final int immediatePreloadCount,
      final int workerThreads,
      final int maxPreloadsPerSecond,
      final int maxQueueDepth,
      final Duration deduplicationWindow,
      final boolean circuitBreakerEnabled,
      final int circuitBreakerThreshold) {
    this.enabled = enabled;
    this.batchSize = batchSize;
    this.preloadInterval = preloadInterval;
    this.immediatePreloadCount = immediatePreloadCount;
    this.workerThreads = workerThreads;
    this.maxPreloadsPerSecond = maxPreloadsPerSecond;
    this.maxQueueDepth = maxQueueDepth;
    this.deduplicationWindow = deduplicationWindow;
    this.circuitBreakerEnabled = circuitBreakerEnabled;
    this.circuitBreakerThreshold = circuitBreakerThreshold;
  }

  /**
   * Returns whether transaction pool preloading is enabled.
   *
   * @return true if enabled, false otherwise
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns the batch size for periodic preloading.
   *
   * @return batch size
   */
  public int getBatchSize() {
    return batchSize;
  }

  /**
   * Returns the interval for periodic preload queue refresh.
   *
   * @return preload interval
   */
  public Duration getPreloadInterval() {
    return preloadInterval;
  }

  /**
   * Returns the number of top transactions to preload immediately when added.
   *
   * @return immediate preload count
   */
  public int getImmediatePreloadCount() {
    return immediatePreloadCount;
  }

  /**
   * Returns the number of worker threads for preload operations.
   *
   * @return worker thread count
   */
  public int getWorkerThreads() {
    return workerThreads;
  }

  /**
   * Returns the maximum number of preload operations per second.
   *
   * @return max preloads per second
   */
  public int getMaxPreloadsPerSecond() {
    return maxPreloadsPerSecond;
  }

  /**
   * Returns the maximum queue depth.
   *
   * @return max queue depth
   */
  public int getMaxQueueDepth() {
    return maxQueueDepth;
  }

  /**
   * Returns the deduplication window duration.
   *
   * @return deduplication window
   */
  public Duration getDeduplicationWindow() {
    return deduplicationWindow;
  }

  /**
   * Returns whether the circuit breaker is enabled.
   *
   * @return true if enabled, false otherwise
   */
  public boolean isCircuitBreakerEnabled() {
    return circuitBreakerEnabled;
  }

  /**
   * Returns the circuit breaker threshold.
   *
   * @return circuit breaker threshold
   */
  public int getCircuitBreakerThreshold() {
    return circuitBreakerThreshold;
  }

  /**
   * Creates a default disabled configuration.
   *
   * @return disabled configuration
   */
  public static TransactionPoolPreloadConfiguration disabled() {
    return builder().enabled(false).build();
  }

  /**
   * Creates a builder with default values.
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for TransactionPoolPreloadConfiguration */
  public static class Builder {
    private boolean enabled = false;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private Duration preloadInterval = DEFAULT_PRELOAD_INTERVAL;
    private int immediatePreloadCount = DEFAULT_IMMEDIATE_PRELOAD_COUNT;
    private int workerThreads = DEFAULT_WORKER_THREADS;
    private int maxPreloadsPerSecond = DEFAULT_MAX_PRELOADS_PER_SECOND;
    private int maxQueueDepth = DEFAULT_MAX_QUEUE_DEPTH;
    private Duration deduplicationWindow = DEFAULT_DEDUPLICATION_WINDOW;
    private boolean circuitBreakerEnabled = true;
    private int circuitBreakerThreshold = DEFAULT_CIRCUIT_BREAKER_THRESHOLD;

    public Builder enabled(final boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder batchSize(final int batchSize) {
      this.batchSize = batchSize;
      return this;
    }

    public Builder preloadInterval(final Duration preloadInterval) {
      this.preloadInterval = preloadInterval;
      return this;
    }

    public Builder immediatePreloadCount(final int immediatePreloadCount) {
      this.immediatePreloadCount = immediatePreloadCount;
      return this;
    }

    public Builder workerThreads(final int workerThreads) {
      this.workerThreads = workerThreads;
      return this;
    }

    public Builder maxPreloadsPerSecond(final int maxPreloadsPerSecond) {
      this.maxPreloadsPerSecond = maxPreloadsPerSecond;
      return this;
    }

    public Builder maxQueueDepth(final int maxQueueDepth) {
      this.maxQueueDepth = maxQueueDepth;
      return this;
    }

    public Builder deduplicationWindow(final Duration deduplicationWindow) {
      this.deduplicationWindow = deduplicationWindow;
      return this;
    }

    public Builder circuitBreakerEnabled(final boolean circuitBreakerEnabled) {
      this.circuitBreakerEnabled = circuitBreakerEnabled;
      return this;
    }

    public Builder circuitBreakerThreshold(final int circuitBreakerThreshold) {
      this.circuitBreakerThreshold = circuitBreakerThreshold;
      return this;
    }

    public TransactionPoolPreloadConfiguration build() {
      return new TransactionPoolPreloadConfiguration(
          enabled,
          batchSize,
          preloadInterval,
          immediatePreloadCount,
          workerThreads,
          maxPreloadsPerSecond,
          maxQueueDepth,
          deduplicationWindow,
          circuitBreakerEnabled,
          circuitBreakerThreshold);
    }
  }
}
