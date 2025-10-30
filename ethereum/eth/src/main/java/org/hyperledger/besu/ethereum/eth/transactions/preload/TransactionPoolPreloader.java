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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionAddedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionDroppedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions;
import org.hyperledger.besu.ethereum.eth.transactions.RemovalReason;
import org.hyperledger.besu.ethereum.eth.transactions.layered.AbstractPrioritizedTransactions;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.BonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preloads account state and storage slots from transactions in the prioritized layer of the
 * transaction pool. This proactively warms the Bonsai merkle trie caches before block execution to
 * improve cache hit rates and reduce block validation latency.
 */
public class TransactionPoolPreloader
    implements PendingTransactionAddedListener, PendingTransactionDroppedListener {

  private static final Logger LOG = LoggerFactory.getLogger(TransactionPoolPreloader.class);

  private final TransactionPoolPreloadConfiguration config;
  private final AbstractPrioritizedTransactions prioritizedTransactions;
  private final BonsaiCachedMerkleTrieLoader merkleTrieLoader;
  private final BonsaiWorldStateKeyValueStorage worldStateStorage;
  private final WorldStateArchive worldStateArchive;

  // Metrics
  private final Counter tasksExecutedCounter;
  private final Counter tasksDeduplicatedCounter;
  private final Counter tasksQueuedCounter;
  private final Counter circuitBreakerTripsCounter;

  // Queue management
  private final BlockingQueue<PreloadTask> preloadQueue;
  private final Set<PreloadTaskKey> recentlyPreloaded;
  private final RateLimiter rateLimiter;

  // Execution management
  private final ScheduledExecutorService scheduler;
  private final ExecutorService workerPool;
  private final AtomicBoolean running;
  private final AtomicBoolean circuitBreakerOpen;

  // Subscription IDs
  private long addedListenerSubscriptionId;
  private long droppedListenerSubscriptionId;

  /**
   * Creates a transaction pool preloader.
   *
   * @param config the preload configuration
   * @param prioritizedTransactions the prioritized transactions layer
   * @param merkleTrieLoader the Bonsai merkle trie loader for caching
   * @param worldStateStorage the world state storage
   * @param worldStateArchive the world state archive
   * @param metricsSystem the metrics system
   */
  public TransactionPoolPreloader(
      final TransactionPoolPreloadConfiguration config,
      final AbstractPrioritizedTransactions prioritizedTransactions,
      final BonsaiCachedMerkleTrieLoader merkleTrieLoader,
      final BonsaiWorldStateKeyValueStorage worldStateStorage,
      final WorldStateArchive worldStateArchive,
      final MetricsSystem metricsSystem) {
    this.config = config;
    this.prioritizedTransactions = prioritizedTransactions;
    this.merkleTrieLoader = merkleTrieLoader;
    this.worldStateStorage = worldStateStorage;
    this.worldStateArchive = worldStateArchive;

    this.preloadQueue = new PriorityBlockingQueue<>(config.getMaxQueueDepth());
    this.recentlyPreloaded = ConcurrentHashMap.newKeySet();
    this.rateLimiter = RateLimiter.create(config.getMaxPreloadsPerSecond());

    this.scheduler = Executors.newSingleThreadScheduledExecutor();
    this.workerPool = Executors.newFixedThreadPool(config.getWorkerThreads());
    this.running = new AtomicBoolean(false);
    this.circuitBreakerOpen = new AtomicBoolean(false);

    // Initialize metrics
    this.tasksExecutedCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "preload_tasks_executed_total",
            "Count of preload tasks successfully executed");

    this.tasksDeduplicatedCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "preload_tasks_deduplicated_total",
            "Count of preload tasks skipped due to deduplication");

    this.tasksQueuedCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "preload_tasks_queued_total",
            "Count of preload tasks added to queue");

    this.circuitBreakerTripsCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "preload_circuit_breaker_trips_total",
            "Count of times circuit breaker engaged");

    // Register gauges
    metricsSystem.createGauge(
        BesuMetricCategory.TRANSACTION_POOL,
        "preload_queue_depth",
        "Current depth of the preload queue",
        () -> (double) preloadQueue.size());

    metricsSystem.createGauge(
        BesuMetricCategory.TRANSACTION_POOL,
        "preload_circuit_breaker_status",
        "Circuit breaker status (0=closed, 1=open)",
        () -> circuitBreakerOpen.get() ? 1.0 : 0.0);
  }

  /**
   * Starts the preloader, subscribing to transaction pool events and beginning periodic queue
   * processing.
   *
   * @param pendingTransactions the pending transactions to subscribe to
   */
  public void start(final PendingTransactions pendingTransactions) {
    if (running.compareAndSet(false, true)) {
      LOG.info(
          "TransactionPoolPreloader started: batchSize={}, interval={}ms, immediateCount={}, workers={}",
          config.getBatchSize(),
          config.getPreloadInterval().toMillis(),
          config.getImmediatePreloadCount(),
          config.getWorkerThreads());

      // Subscribe to transaction pool events
      addedListenerSubscriptionId = pendingTransactions.subscribePendingTransactions(this);
      droppedListenerSubscriptionId = pendingTransactions.subscribeDroppedTransactions(this);

      // Schedule periodic queue refresh
      scheduler.scheduleAtFixedRate(
          this::refreshPreloadQueue,
          0,
          config.getPreloadInterval().toMillis(),
          TimeUnit.MILLISECONDS);

      // Schedule periodic deduplication cleanup
      scheduler.scheduleAtFixedRate(
          recentlyPreloaded::clear,
          config.getDeduplicationWindow().toMillis(),
          config.getDeduplicationWindow().toMillis(),
          TimeUnit.MILLISECONDS);

      // Start worker threads for queue processing
      for (int i = 0; i < config.getWorkerThreads(); i++) {
        workerPool.submit(this::processQueue);
      }
    }
  }

  /** Stops the preloader, unsubscribing from events and shutting down executors. */
  public void stop(final PendingTransactions pendingTransactions) {
    if (running.compareAndSet(true, false)) {
      LOG.info("TransactionPoolPreloader stopping...");

      // Unsubscribe from events
      pendingTransactions.unsubscribePendingTransactions(addedListenerSubscriptionId);
      pendingTransactions.unsubscribeDroppedTransactions(droppedListenerSubscriptionId);

      // Shutdown executors
      scheduler.shutdown();
      workerPool.shutdown();

      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
        if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
          workerPool.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        workerPool.shutdownNow();
        Thread.currentThread().interrupt();
      }

      LOG.info("TransactionPoolPreloader stopped");
    }
  }

  @Override
  public void onTransactionAdded(final Transaction transaction) {
    if (!running.get() || circuitBreakerOpen.get()) {
      return;
    }

    LOG.debug("Transaction added to pool: {}", transaction.getHash());
    // Implementation will check if this is a top transaction and preload immediately
  }

  @Override
  public void onTransactionDropped(final Transaction transaction, final RemovalReason reason) {
    if (!running.get()) {
      return;
    }

    LOG.debug("Transaction dropped from pool: {}, reason: {}", transaction.getHash(), reason);
    // No action needed - rely on lazy invalidation
  }

  /**
   * Called when a new block is added to the canonical chain. Clears recent preload tracking to
   * allow re-preloading with updated state.
   *
   * @param blockHeader the added block header
   */
  public void onBlockAdded(final BlockHeader blockHeader) {
    if (!running.get()) {
      return;
    }

    LOG.debug("Block added: {}, clearing preload cache", blockHeader.getNumber());
    recentlyPreloaded.clear();
    refreshPreloadQueue();
  }

  /**
   * Refreshes the preload queue by fetching top transactions from the prioritized layer and queuing
   * their state for preloading.
   */
  private void refreshPreloadQueue() {
    if (!running.get() || circuitBreakerOpen.get()) {
      return;
    }

    try {
      // Check circuit breaker
      if (config.isCircuitBreakerEnabled()
          && preloadQueue.size() >= config.getCircuitBreakerThreshold()) {
        circuitBreakerOpen.set(true);
        circuitBreakerTripsCounter.inc();
        LOG.warn(
            "Preload queue depth {} exceeds threshold {}, circuit breaker engaged",
            preloadQueue.size(),
            config.getCircuitBreakerThreshold());
        return;
      }

      // Reset circuit breaker if queue has drained
      if (circuitBreakerOpen.get()
          && preloadQueue.size() < config.getCircuitBreakerThreshold() / 2) {
        circuitBreakerOpen.set(false);
        LOG.info("Preload queue drained, circuit breaker reset");
      }

      // Get top transactions from prioritized layer
      final List<PendingTransaction> topTransactions = getTopPrioritizedTransactions();

      LOG.debug("Refreshing preload queue with {} top transactions", topTransactions.size());

      // Queue preload tasks for each transaction
      for (PendingTransaction pendingTx : topTransactions) {
        preloadTransaction(pendingTx);
      }

    } catch (Exception e) {
      LOG.error("Error refreshing preload queue", e);
    }
  }

  /**
   * Gets the top transactions from the prioritized layer, ordered by score descending.
   *
   * @return list of top pending transactions
   */
  private List<PendingTransaction> getTopPrioritizedTransactions() {
    try {
      // Get transactions by score (descending order)
      final var txsByScore = prioritizedTransactions.getByScore();

      // Flatten and collect up to batchSize transactions
      return txsByScore.values().stream()
          .flatMap(List::stream) // List<SenderPendingTransactions>
          .flatMap(
              senderTxs ->
                  senderTxs.pendingTransactions().stream()) // Extract individual transactions
          .limit(config.getBatchSize())
          .toList();

    } catch (Exception e) {
      LOG.error("Error getting top prioritized transactions", e);
      return List.of();
    }
  }

  /**
   * Queues preload tasks for a transaction's state (sender, recipient, access list).
   *
   * @param pendingTx the pending transaction
   */
  private void preloadTransaction(final PendingTransaction pendingTx) {
    final Transaction tx = pendingTx.getTransaction();
    final int priority = calculatePriority(pendingTx);

    // Preload sender account
    queueAccountPreload(tx.getSender(), priority);

    // Preload recipient account if present
    tx.getTo().ifPresent(recipient -> queueAccountPreload(recipient, priority));

    // Preload access list entries if present
    tx.getAccessList()
        .ifPresent(
            accessList ->
                accessList.forEach(
                    entry -> {
                      queueAccountPreload(entry.address(), priority);
                      entry
                          .storageKeys()
                          .forEach(
                              storageKey ->
                                  queueStoragePreload(entry.address(), storageKey, priority));
                    }));
  }

  /**
   * Calculates the priority for preload tasks based on transaction score.
   *
   * @param pendingTx the pending transaction
   * @return priority value
   */
  private int calculatePriority(final PendingTransaction pendingTx) {
    int priority = pendingTx.getScore();
    if (pendingTx.hasPriority()) {
      priority += 128; // Boost priority sender transactions
    }
    return priority;
  }

  /**
   * Queues an account preload task.
   *
   * @param address the account address
   * @param priority the priority
   */
  private void queueAccountPreload(final Address address, final int priority) {
    final PreloadTaskKey key = new PreloadTaskKey(PreloadTask.PreloadType.ACCOUNT, address, null);
    if (recentlyPreloaded.contains(key)) {
      tasksDeduplicatedCounter.inc();
      return;
    }

    final PreloadTask task =
        new PreloadTask(
            PreloadTask.PreloadType.ACCOUNT, address, null, priority, System.currentTimeMillis());

    if (preloadQueue.offer(task)) {
      tasksQueuedCounter.inc();
      recentlyPreloaded.add(key);
    }
  }

  /**
   * Queues a storage slot preload task.
   *
   * @param address the account address
   * @param storageKey the storage key bytes
   * @param priority the priority
   */
  private void queueStoragePreload(
      final Address address, final org.apache.tuweni.bytes.Bytes32 storageKey, final int priority) {
    final org.hyperledger.besu.datatypes.StorageSlotKey slotKey =
        new org.hyperledger.besu.datatypes.StorageSlotKey(
            org.apache.tuweni.units.bigints.UInt256.fromBytes(
                org.apache.tuweni.bytes.Bytes.wrap(storageKey)));

    final PreloadTaskKey key =
        new PreloadTaskKey(PreloadTask.PreloadType.STORAGE, address, storageKey);
    if (recentlyPreloaded.contains(key)) {
      tasksDeduplicatedCounter.inc();
      return;
    }

    final PreloadTask task =
        new PreloadTask(
            PreloadTask.PreloadType.STORAGE,
            address,
            slotKey,
            priority,
            System.currentTimeMillis());

    if (preloadQueue.offer(task)) {
      tasksQueuedCounter.inc();
      recentlyPreloaded.add(key);
    }
  }

  /** Worker thread method that continuously processes the preload queue. */
  private void processQueue() {
    while (running.get()) {
      try {
        final PreloadTask task = preloadQueue.poll(1, TimeUnit.SECONDS);
        if (task != null) {
          rateLimiter.acquire();
          executePreloadTask(task);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        LOG.error("Error processing preload task", e);
      }
    }
  }

  /**
   * Executes a preload task by calling the appropriate merkle trie loader method.
   *
   * @param task the preload task
   */
  private void executePreloadTask(final PreloadTask task) {
    try {
      final Hash worldStateRootHash = getWorldStateRootHash();

      switch (task.getType()) {
        case ACCOUNT:
          merkleTrieLoader.preLoadAccount(worldStateStorage, worldStateRootHash, task.getAddress());
          LOG.debug("Preloaded account {} (priority: {})", task.getAddress(), task.getPriority());
          break;

        case STORAGE:
          if (task.getSlot() != null) {
            merkleTrieLoader.preLoadStorageSlot(
                worldStateStorage, task.getAddress(), task.getSlot());
            LOG.debug(
                "Preloaded storage slot {} for account {} (priority: {})",
                task.getSlot(),
                task.getAddress(),
                task.getPriority());
          }
          break;

        case CODE:
          // Code preloading could be added here if needed
          break;
      }

      tasksExecutedCounter.inc();

    } catch (Exception e) {
      LOG.debug("Error executing preload task: {}", task, e);
    }
  }

  /**
   * Gets the current world state root hash.
   *
   * @return world state root hash
   */
  private Hash getWorldStateRootHash() {
    try {
      return worldStateArchive.getWorldState().rootHash();
    } catch (Exception e) {
      LOG.debug("Error getting world state root hash", e);
      return Hash.EMPTY;
    }
  }

  /** Key for deduplication of preload tasks. */
  private record PreloadTaskKey(
      PreloadTask.PreloadType type, Address address, org.apache.tuweni.bytes.Bytes32 slotHash) {}
}
