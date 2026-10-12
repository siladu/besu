/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.consensus.qbft.adaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.consensus.common.bft.BftBlockHeaderFunctions;
import org.hyperledger.besu.consensus.common.bft.BftHelpers;
import org.hyperledger.besu.consensus.qbft.QbftExtraDataCodec;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.BlockValidator;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.BlockImporter;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.BlockHeaderValidator;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * THROWAWAY: PR #11469 review point 1, step 4. A round-0 proposal with a genuine header and a
 * tampered body passes the validator adaptor on a cache hit, and the importer adaptor appends the
 * tampered body with the genuine block's receipts.
 */
class TmpCachedBodyImportTest {
  private final QbftExtraDataCodec codec = new QbftExtraDataCodec();
  private final BlockDataGenerator generator = new BlockDataGenerator();

  private final BlockHeader parentHeader = new BlockHeaderTestFixture().number(0).buildHeader();

  // Genuine round-0 proposal block, hashed the way the proposal codec hashes it
  private final Block genuine =
      generator.block(
          BlockDataGenerator.BlockOptions.create()
              .setBlockNumber(1)
              .setParentHash(parentHeader.getHash())
              .setExtraData(
                  QbftExtraDataCodec.encodeFromAddresses(
                      List.of(Address.fromHexString("0x01"), Address.fromHexString("0x02"))))
              .setBlockHeaderFunctions(BftBlockHeaderFunctions.forCommittedSeal(codec)));
  private final List<TransactionReceipt> genuineReceipts = generator.receipts(genuine);

  // Same header, different body
  private final Block tampered =
      new Block(
          genuine.getHeader(),
          new BlockBody(List.of(generator.transaction()), Collections.emptyList()));

  @Test
  void tamperedBodyIsValidatedFromCacheAndAppended() {
    assertThat(tampered.getHash()).isEqualTo(genuine.getHash());
    assertThat(BodyValidation.transactionsRoot(tampered.getBody().getTransactions()))
        .isNotEqualTo(tampered.getHeader().getTransactionsRoot());

    final ValidatedBlockCache cache = new ValidatedBlockCache();
    final BlockValidator blockValidator = mock(BlockValidator.class);
    final ProtocolContext protocolContext = mock(ProtocolContext.class);
    when(blockValidator.validateAndProcessBlock(
            protocolContext,
            genuine,
            HeaderValidationMode.LIGHT,
            HeaderValidationMode.FULL,
            Optional.empty(),
            false))
        .thenReturn(
            new BlockProcessingResult(
                Optional.of(
                    new BlockProcessingOutputs(mock(MutableWorldState.class), genuineReceipts))));
    final QbftBlockValidatorAdaptor validator =
        new QbftBlockValidatorAdaptor(blockValidator, protocolContext, cache);

    // Step 2: genuine block validated (RoundChange prepared block), outputs cached
    assertThat(validator.validateBlock(new QbftBlockAdaptor(genuine), Optional.empty()).success())
        .isTrue();
    // Step 3: tampered proposal passes without being executed or body-validated
    assertThat(validator.validateBlock(new QbftBlockAdaptor(tampered), Optional.empty()).success())
        .isTrue();
    verify(blockValidator, times(1))
        .validateAndProcessBlock(any(), any(), any(), any(), any(), eq(false));

    // Step 4: commit in round 0 seals the tampered proposal; on-chain hash equals cache key
    final Block sealedTampered =
        BftHelpers.createSealedBlock(codec, tampered, 0, Collections.emptyList());
    assertThat(sealedTampered.getHash()).isEqualTo(genuine.getHash());
    assertThat(sealedTampered.getBody()).isEqualTo(tampered.getBody());

    final BlockImporter blockImporter = mock(BlockImporter.class);
    final BlockHeaderValidator headerValidator = mock(BlockHeaderValidator.class);
    final MutableBlockchain blockchain = mock(MutableBlockchain.class);
    final PathBasedWorldStateProvider worldStateArchive = mock(PathBasedWorldStateProvider.class);
    final BonsaiWorldStateKeyValueStorage worldStateStorage =
        mock(BonsaiWorldStateKeyValueStorage.class);
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    when(protocolContext.getWorldStateArchive()).thenReturn(worldStateArchive);
    when(blockchain.getChainHeadHash()).thenReturn(parentHeader.getHash());
    when(blockchain.getBlockHeader(parentHeader.getHash())).thenReturn(Optional.of(parentHeader));
    when(worldStateArchive.getWorldStateKeyValueStorage()).thenReturn(worldStateStorage);
    // trie log saved when the genuine block was executed
    when(worldStateStorage.getTrieLog(genuine.getHash())).thenReturn(Optional.of(new byte[0]));
    when(headerValidator.validateHeader(
            sealedTampered.getHeader(), parentHeader, protocolContext, HeaderValidationMode.FULL))
        .thenReturn(true);
    when(worldStateArchive.getWorldState(any()))
        .thenReturn(Optional.of(mock(MutableWorldState.class)));

    final QbftBlockImporterAdaptor importer =
        new QbftBlockImporterAdaptor(blockImporter, headerValidator, protocolContext, cache);
    assertThat(importer.importBlock(new QbftBlockAdaptor(sealedTampered), Optional.empty()))
        .isTrue();

    verify(blockchain)
        .appendBlock(
            argThat(b -> b.getBody().equals(tampered.getBody())),
            eq(genuineReceipts),
            eq(Optional.empty()));
    verifyNoInteractions(blockImporter);
  }
}
