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

package org.hyperledger.besu.ethereum.bonsai.trielog;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.ethereum.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.BlockHeader;

import java.util.Optional;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

public class TrieLogPrunerTest {

  private BonsaiWorldStateKeyValueStorage worldState;
  private Blockchain blockchain;

  @BeforeEach
  public void setup() {
    worldState = Mockito.mock(BonsaiWorldStateKeyValueStorage.class);
    blockchain = Mockito.mock(Blockchain.class);
  }

  @SuppressWarnings("BannedMethod")
  @Test
  public void trieLogs_pruned_in_reverse_order_within_pruning_window() {
    Configurator.setLevel(LogManager.getLogger(TrieLogPruner.class).getName(), Level.TRACE);

    // Given

    // pruning window is below numBlocksToRetain and inside the pruningWindowSize offset.
    final long blocksToRetain = 3;
    final int pruningWindowSize = 2;
    TrieLogPruner trieLogPruner =
        new TrieLogPruner(worldState, blockchain, blocksToRetain, pruningWindowSize);

    final byte[] key0 = new byte[] {1, 2, 3}; // older block outside the prune window
    final byte[] key1 = new byte[] {1, 2, 3}; // block inside the prune window
    final byte[] key2 = new byte[] {4, 5, 6}; // same block (fork)
    final byte[] key3 = new byte[] {7, 8, 9}; // different block inside the prune window
    final byte[] key4 = new byte[] {10, 11, 12}; // retained block
    final byte[] key5 = new byte[] {13, 14, 15}; // different retained block
    final byte[] key6 = new byte[] {7, 8, 9}; // another retained block
    final long block0 = 1000L;
    final long block1 = 1001L;
    final long block2 = 1002L;
    final long block3 = 1003L;
    final long block4 = 1004L;
    final long block5 = 1005L;

    trieLogPruner.cacheForLaterPruning(block0, key0); // older block outside prune window
    trieLogPruner.cacheForLaterPruning(block1, key1); // block inside the prune window
    trieLogPruner.cacheForLaterPruning(block1, key2); // same block number (fork)
    trieLogPruner.cacheForLaterPruning(block2, key3); // different block inside prune window
    trieLogPruner.cacheForLaterPruning(block3, key4); // retained block
    trieLogPruner.cacheForLaterPruning(block4, key5); // different retained block
    trieLogPruner.cacheForLaterPruning(block5, key6); // another retained block

    when(blockchain.getChainHeadBlockNumber()).thenReturn(block5);

    // When
    trieLogPruner.pruneFromCache();

    // Then
    InOrder inOrder = Mockito.inOrder(worldState);
    inOrder.verify(worldState, times(1)).pruneTrieLog(key3);
    inOrder.verify(worldState, times(1)).pruneTrieLog(key1);
    inOrder.verify(worldState, times(1)).pruneTrieLog(key2);

    // Subsequent run should add one more block, then prune two oldest remaining keys
    long block6 = 1006L;
    trieLogPruner.cacheForLaterPruning(block6, new byte[] {1, 2, 3});
    when(blockchain.getChainHeadBlockNumber()).thenReturn(block6);

    trieLogPruner.pruneFromCache();

    inOrder.verify(worldState, times(1)).pruneTrieLog(key4);
    inOrder.verify(worldState, times(1)).pruneTrieLog(key0);
  }

  @SuppressWarnings("BannedMethod")
  @Test
  public void retain_non_finalized_blocks() {
    Configurator.setLevel(LogManager.getLogger(TrieLogPruner.class).getName(), Level.TRACE);

    // Given
    // finalizedBlockHeight < configuredRetainHeight
    final long finalizedBlockHeight = 1;
    final long configuredRetainHeight = 3;
    TrieLogPruner trieLogPruner =
        setupPrunerAndFinalizedBlock(configuredRetainHeight, finalizedBlockHeight);

    // When
    trieLogPruner.pruneFromCache();

    // Then
    verify(worldState, times(1)).pruneTrieLog(key(1)); // should prune (finalized)
    verify(worldState, never()).pruneTrieLog(key(2)); // would prune but (NOT finalized)
    verify(worldState, never()).pruneTrieLog(key(3)); // would prune but (NOT finalized)
    verify(worldState, never()).pruneTrieLog(key(4)); // retained block (NOT finalized)
    verify(worldState, never()).pruneTrieLog(key(5)); // chain height (NOT finalized)
  }

  @SuppressWarnings("BannedMethod")
  @Test
  public void boundary_test_when_configured_retain_equals_finalized_block() {
    Configurator.setLevel(LogManager.getLogger(TrieLogPruner.class).getName(), Level.TRACE);

    // Given
    // finalizedBlockHeight == configuredRetainHeight
    final long finalizedBlockHeight = 2;
    final long configuredRetainHeight = 2;

    TrieLogPruner trieLogPruner =
        setupPrunerAndFinalizedBlock(configuredRetainHeight, finalizedBlockHeight);

    // When
    trieLogPruner.pruneFromCache();

    // Then
    verify(worldState, times(1)).pruneTrieLog(key(1)); // should prune (finalized)
    verify(worldState, never()).pruneTrieLog(key(2)); // retained block (finalized)
    verify(worldState, never()).pruneTrieLog(key(3)); // retained block (NOT finalized)
    verify(worldState, never()).pruneTrieLog(key(4)); // retained block (NOT finalized)
    verify(worldState, never()).pruneTrieLog(key(5)); // chain height (NOT finalized)
  }

  @SuppressWarnings("BannedMethod")
  @Test
  public void use_configured_retain_when_finalized_block_is_higher() {
    Configurator.setLevel(LogManager.getLogger(TrieLogPruner.class).getName(), Level.TRACE);

    // Given
    // finalizedBlockHeight > configuredRetainHeight
    final long finalizedBlockHeight = 4;
    final long configuredRetainHeight = 3;

    final TrieLogPruner trieLogPruner =
        setupPrunerAndFinalizedBlock(configuredRetainHeight, finalizedBlockHeight);

    // When
    trieLogPruner.pruneFromCache();

    // Then
    final InOrder inOrder = Mockito.inOrder(worldState);
    inOrder.verify(worldState, times(1)).pruneTrieLog(key(2)); // should prune (finalized)
    inOrder.verify(worldState, times(1)).pruneTrieLog(key(1)); // should prune (finalized)
    verify(worldState, never()).pruneTrieLog(key(3)); // retained block (finalized)
    verify(worldState, never()).pruneTrieLog(key(4)); // retained block (finalized)
    verify(worldState, never()).pruneTrieLog(key(5)); // chain height (NOT finalized)
  }

  private TrieLogPruner setupPrunerAndFinalizedBlock(
      final long configuredRetainHeight, final long finalizedBlockHeight) {
    final long chainHeight = 5;
    final long configuredRetainAboveHeight = configuredRetainHeight - 1;
    final long blocksToRetain = chainHeight - configuredRetainAboveHeight;
    final int pruningWindowSize = (int) chainHeight;

    final BlockHeader finalizedHeader = new BlockDataGenerator().header(finalizedBlockHeight);
    when(blockchain.getFinalized()).thenReturn(Optional.of(finalizedHeader.getBlockHash()));
    when(blockchain.getBlockHeader(finalizedHeader.getBlockHash()))
        .thenReturn(Optional.of(finalizedHeader));
    when(blockchain.getChainHeadBlockNumber()).thenReturn(chainHeight);
    TrieLogPruner trieLogPruner =
        new TrieLogPruner(worldState, blockchain, blocksToRetain, pruningWindowSize);

    trieLogPruner.cacheForLaterPruning(1, key(1));
    trieLogPruner.cacheForLaterPruning(2, key(2));
    trieLogPruner.cacheForLaterPruning(3, key(3));
    trieLogPruner.cacheForLaterPruning(4, key(4));
    trieLogPruner.cacheForLaterPruning(5, key(5));

    return trieLogPruner;
  }

  @Test
  public void initialize_preloads_cache_and_prunes_orphaned_blocks() {
    // Given
    int loadingLimit = 2;
    final BlockDataGenerator generator = new BlockDataGenerator();
    final BlockHeader header1 = generator.header(1);
    final BlockHeader header2 = generator.header(2);
    when(worldState.streamTrieLogKeys(loadingLimit))
        .thenReturn(Stream.of(header1.getBlockHash().toArray(), header2.getBlockHash().toArray()));
    when(blockchain.getBlockHeader(header1.getBlockHash())).thenReturn(Optional.of(header1));
    when(blockchain.getBlockHeader(header2.getBlockHash())).thenReturn(Optional.empty());

    // When
    TrieLogPruner trieLogPruner = new TrieLogPruner(worldState, blockchain, 3, loadingLimit);
    trieLogPruner.initialize();

    // Then
    verify(worldState, times(1)).streamTrieLogKeys(2);
    verify(worldState, times(1)).pruneTrieLog(header2.getBlockHash().toArray());
  }

  private byte[] key(final int k) {
    return new byte[] {(byte) k};
  }
}
