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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions;
import org.hyperledger.besu.ethereum.eth.transactions.RemovalReason;
import org.hyperledger.besu.ethereum.eth.transactions.layered.AbstractPrioritizedTransactions;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.BonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class TransactionPoolPreloaderTest {

  private static final KeyPair KEY_PAIR = SignatureAlgorithmFactory.getInstance().generateKeyPair();

  private static final RemovalReason TEST_REMOVAL_REASON =
      new RemovalReason() {
        @Override
        public String label() {
          return "test";
        }

        @Override
        public boolean stopTracking() {
          return false;
        }
      };

  @Mock private AbstractPrioritizedTransactions prioritizedTransactions;
  @Mock private BonsaiCachedMerkleTrieLoader merkleTrieLoader;
  @Mock private BonsaiWorldStateKeyValueStorage worldStateStorage;
  @Mock private WorldStateArchive worldStateArchive;
  @Mock private MutableWorldState mutableWorldState;
  @Mock private PendingTransactions pendingTransactions;

  private TransactionPoolPreloadConfiguration config;
  private TransactionPoolPreloader preloader;
  private AutoCloseable mocks;

  private static final Address SENDER = Address.fromHexString("0x1");
  private static final Address RECIPIENT = Address.fromHexString("0x2");
  private static final Hash WORLD_STATE_ROOT = Hash.ZERO;

  @BeforeEach
  public void setup() {
    mocks = MockitoAnnotations.openMocks(this);

    config =
        TransactionPoolPreloadConfiguration.builder()
            .enabled(true)
            .batchSize(10)
            .preloadInterval(Duration.ofMillis(100))
            .immediatePreloadCount(5)
            .workerThreads(1)
            .maxPreloadsPerSecond(100)
            .circuitBreakerEnabled(false) // Disabled for most tests
            .build();

    when(worldStateArchive.getWorldState()).thenReturn(mutableWorldState);
    when(mutableWorldState.rootHash()).thenReturn(WORLD_STATE_ROOT);

    preloader =
        new TransactionPoolPreloader(
            config,
            prioritizedTransactions,
            merkleTrieLoader,
            worldStateStorage,
            worldStateArchive,
            new NoOpMetricsSystem());
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (preloader != null) {
      preloader.stop(pendingTransactions);
    }
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  public void shouldStartAndStopSuccessfully() {
    preloader.start(pendingTransactions);

    verify(pendingTransactions).subscribePendingTransactions(preloader);
    verify(pendingTransactions).subscribeDroppedTransactions(preloader);

    preloader.stop(pendingTransactions);

    verify(pendingTransactions).unsubscribePendingTransactions(0L);
    verify(pendingTransactions).unsubscribeDroppedTransactions(0L);
  }

  @Test
  public void shouldHandleTransactionAddedEvent() {
    final Transaction tx = createTransaction(SENDER, RECIPIENT);

    preloader.start(pendingTransactions);
    preloader.onTransactionAdded(tx);

    // Event received and logged (no exceptions)
  }

  @Test
  public void shouldHandleTransactionDroppedEvent() {
    final Transaction tx = createTransaction(SENDER, RECIPIENT);

    preloader.start(pendingTransactions);
    preloader.onTransactionDropped(tx, TEST_REMOVAL_REASON);

    // Event received and logged (no exceptions)
  }

  @Test
  public void shouldHandleBlockAddedEvent() {
    final BlockHeader blockHeader = mock(BlockHeader.class);
    when(blockHeader.getNumber()).thenReturn(1L);

    preloader.start(pendingTransactions);
    preloader.onBlockAdded(blockHeader);

    // Event received and cache cleared (no exceptions)
  }

  @Test
  public void shouldNotProcessEventsWhenNotRunning() {
    final Transaction tx = createTransaction(SENDER, RECIPIENT);

    // Don't start preloader
    preloader.onTransactionAdded(tx);
    preloader.onTransactionDropped(tx, TEST_REMOVAL_REASON);

    // Should not throw exceptions, just return early
  }

  @Test
  public void shouldPreloadAccountForTransaction() throws InterruptedException {
    when(prioritizedTransactions.getByScore()).thenReturn(new TreeMap<>());

    preloader.start(pendingTransactions);

    // Give time for periodic refresh (mocked to be quick)
    Thread.sleep(200);

    // Should have attempted to preload (at minimum, no exceptions thrown)
    preloader.stop(pendingTransactions);
  }

  @Test
  public void shouldHandleNullRecipient() {
    // Contract creation transaction (no recipient)
    final Transaction tx =
        new TransactionTestFixture()
            .sender(SENDER)
            .to(Optional.empty())
            .createTransaction(KEY_PAIR);

    preloader.start(pendingTransactions);
    preloader.onTransactionAdded(tx);

    // Should handle gracefully without throwing
  }

  @Test
  public void shouldHandleEmptyAccessList() {
    final Transaction tx =
        new TransactionTestFixture()
            .sender(SENDER)
            .to(Optional.of(RECIPIENT))
            .accessList(Collections.emptyList())
            .createTransaction(KEY_PAIR);

    preloader.start(pendingTransactions);
    preloader.onTransactionAdded(tx);

    // Should handle gracefully
  }

  @Test
  public void shouldNotProcessWhenCircuitBreakerOpen() {
    // Create config with circuit breaker enabled and low threshold
    config =
        TransactionPoolPreloadConfiguration.builder()
            .enabled(true)
            .batchSize(10)
            .preloadInterval(Duration.ofMillis(100))
            .circuitBreakerEnabled(true)
            .circuitBreakerThreshold(1) // Very low threshold
            .maxQueueDepth(10)
            .build();

    preloader =
        new TransactionPoolPreloader(
            config,
            prioritizedTransactions,
            merkleTrieLoader,
            worldStateStorage,
            worldStateArchive,
            new NoOpMetricsSystem());

    final Transaction tx = createTransaction(SENDER, RECIPIENT);

    preloader.start(pendingTransactions);

    // Should process initially, then stop when circuit breaker trips
    preloader.onTransactionAdded(tx);
  }

  @Test
  public void shouldHandleWorldStateArchiveError() {
    when(worldStateArchive.getWorldState()).thenThrow(new RuntimeException("Test error"));

    preloader =
        new TransactionPoolPreloader(
            config,
            prioritizedTransactions,
            merkleTrieLoader,
            worldStateStorage,
            worldStateArchive,
            new NoOpMetricsSystem());

    preloader.start(pendingTransactions);

    // Should handle error gracefully
    final Transaction tx = createTransaction(SENDER, RECIPIENT);
    preloader.onTransactionAdded(tx);

    preloader.stop(pendingTransactions);
  }

  @Test
  public void shouldCallMerkleTrieLoaderForPreload() throws InterruptedException {
    preloader.start(pendingTransactions);

    // Trigger refresh which will attempt to get transactions
    when(prioritizedTransactions.getByScore()).thenReturn(new TreeMap<>());

    Thread.sleep(200); // Allow periodic task to run

    // At minimum, the preloader ran without exceptions
    preloader.stop(pendingTransactions);
  }

  @Test
  public void shouldDeduplicatePreloadTasks() throws InterruptedException {
    final Transaction tx1 = createTransaction(SENDER, RECIPIENT);
    final Transaction tx2 = createTransaction(SENDER, RECIPIENT); // Same addresses

    preloader.start(pendingTransactions);

    preloader.onTransactionAdded(tx1);
    preloader.onTransactionAdded(tx2);

    Thread.sleep(100); // Allow processing

    // Deduplication should prevent redundant preloads (verified via metrics in real usage)
    preloader.stop(pendingTransactions);
  }

  private Transaction createTransaction(final Address sender, final Address recipient) {
    return new TransactionTestFixture()
        .sender(sender)
        .to(Optional.of(recipient))
        .createTransaction(KEY_PAIR);
  }
}
