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
package org.hyperledger.besu.consensus.qbft.core.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.consensus.qbft.core.support.IntegrationTestHelpers.createValidPreparedCertificate;

import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier;
import org.hyperledger.besu.consensus.qbft.adaptor.AdaptorUtil;
import org.hyperledger.besu.consensus.qbft.adaptor.QbftBlockAdaptor;
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.Prepare;
import org.hyperledger.besu.consensus.qbft.core.statemachine.PreparedCertificate;
import org.hyperledger.besu.consensus.qbft.core.support.RoundSpecificPeers;
import org.hyperledger.besu.consensus.qbft.core.support.TestContext;
import org.hyperledger.besu.consensus.qbft.core.support.TestContextBuilder;
import org.hyperledger.besu.consensus.qbft.core.support.ValidatorPeer;
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * THROWAWAY: PR #11469 review point 1. A Byzantine round-0 proposer primes the local node's
 * ValidatedBlockCache with the genuine block (via a future-round RoundChange carrying a real
 * prepared certificate), then sends the local node a round-0 proposal with the same header and a
 * different body. The local node accepts it without body validation and sends a Prepare.
 */
public class TmpCachedBodyBypassTest {

  private final long blockTimeStamp = 100;
  private final Clock fixedClock =
      Clock.fixed(Instant.ofEpochSecond(blockTimeStamp), ZoneId.systemDefault());

  private final TestContext context =
      new TestContextBuilder()
          .validatorCount(4)
          .indexOfFirstLocallyProposedBlock(0)
          .clock(fixedClock)
          .buildAndStart();
  private final ConsensusRoundIdentifier round0 = new ConsensusRoundIdentifier(1, 0);
  private final ConsensusRoundIdentifier round1 = new ConsensusRoundIdentifier(1, 1);
  private final RoundSpecificPeers peers = context.roundSpecificPeers(round0);
  private final ValidatorPeer byzantineProposer = peers.getProposer();

  private final QbftBlock genuineBlock =
      context.createBlockForProposalFromChainHead(15, byzantineProposer.getNodeAddress());
  private final QbftBlock tamperedBlock = withTamperedBody(genuineBlock);

  @Test
  void tamperedBodyHasSameHashButDoesNotMatchHeader() {
    final Block genuine = AdaptorUtil.toBesuBlock(genuineBlock);
    final Block tampered = AdaptorUtil.toBesuBlock(tamperedBlock);
    assertThat(tampered.getHash()).isEqualTo(genuine.getHash());
    assertThat(BodyValidation.transactionsRoot(tampered.getBody().getTransactions()))
        .isNotEqualTo(tampered.getHeader().getTransactionsRoot());
  }

  @Test
  void control_tamperedProposalIsRejectedWithoutPriming() {
    byzantineProposer.injectProposal(round0, tamperedBlock);

    peers.verifyNoMessagesReceived();
  }

  @Test
  void tamperedProposalIsPreparedAfterCacheIsPrimedByRoundChange() {
    // Genuine block was prepared by all remote validators (Byzantine proposer + 2 honest).
    final PreparedCertificate preparedCertificate =
        createValidPreparedCertificate(context, round0, genuineBlock);

    // Byzantine proposer sends a future-round RoundChange carrying the genuine block. The local
    // node fully validates it and caches its outputs under the header hash.
    byzantineProposer.injectRoundChange(round1, Optional.of(preparedCertificate));
    peers.verifyNoMessagesReceived();

    // Same header, different body, for the current round.
    byzantineProposer.injectProposal(round0, tamperedBlock);

    // Local node accepted the proposal (cache hit, no body validation) and prepared it.
    final Prepare expectedPrepare =
        context.getLocalNodeMessageFactory().createPrepare(round0, tamperedBlock.getHash());
    peers.verifyMessagesReceived(expectedPrepare);
  }

  private static QbftBlock withTamperedBody(final QbftBlock block) {
    final Block besuBlock = AdaptorUtil.toBesuBlock(block);
    final BlockBody tamperedBody =
        new BlockBody(
            List.of(
                new TransactionTestFixture()
                    .chainId(Optional.of(BigInteger.ONE))
                    .gasPrice(Wei.ZERO)
                    .gasLimit(21_000)
                    .to(Optional.of(Address.fromHexString("0x01")))
                    .createTransaction(SignatureAlgorithmFactory.getInstance().generateKeyPair())),
            Collections.emptyList());
    return new QbftBlockAdaptor(new Block(besuBlock.getHeader(), tamperedBody));
  }
}
