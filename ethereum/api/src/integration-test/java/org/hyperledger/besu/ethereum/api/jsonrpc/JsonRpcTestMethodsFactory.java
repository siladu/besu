/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.api.jsonrpc;

import static org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider.createInMemoryBlockchain;
import static org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider.createInMemoryWorldStateArchive;
import static org.mockito.Mockito.mock;

import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.config.StubGenesisConfigOptions;
import org.hyperledger.besu.consensus.merge.MergeContext;
import org.hyperledger.besu.consensus.merge.PostMergeContext;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.filter.FilterManager;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.filter.FilterManagerBuilder;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.methods.JsonRpcMethodsFactory;
import org.hyperledger.besu.ethereum.api.jsonrpc.websocket.WebSocketConfiguration;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.blockcreation.PoWMiningCoordinator;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockImporter;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.core.Synchronizer;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.p2p.network.P2PNetwork;
import org.hyperledger.besu.ethereum.permissioning.AccountLocalConfigPermissioningController;
import org.hyperledger.besu.ethereum.permissioning.NodeLocalConfigPermissioningController;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.metrics.ObservableMetricsSystem;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.metrics.prometheus.MetricsConfiguration;
import org.hyperledger.besu.nat.NatService;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

/** Provides a facade to construct the JSON-RPC component. */
public class JsonRpcTestMethodsFactory {

  private static final String CLIENT_VERSION = "TestClientVersion/0.1.0";
  private static final BigInteger NETWORK_ID = BigInteger.valueOf(123);

  private final BlockchainImporter importer;
  private final MutableBlockchain blockchain;
  private final WorldStateArchive stateArchive;
  private final ProtocolContext context;
  private final BlockchainQueries blockchainQueries;
  private final Synchronizer synchronizer;

  public JsonRpcTestMethodsFactory(final BlockchainImporter importer) {
    this.importer = importer;
    this.blockchain = createInMemoryBlockchain(importer.getGenesisBlock());
    this.stateArchive = createInMemoryWorldStateArchive();
    this.importer.getGenesisState().writeStateTo(stateArchive.getMutable());
    final ProtocolSchedule protocolSchedule = importer.getProtocolSchedule();
    final MergeContext consensusContext = createConsensusContext(blockchain, importer.getGenesisConfigOptions());
    this.context = new ProtocolContext(blockchain, stateArchive, consensusContext);

    this.synchronizer = mock(Synchronizer.class);


    for (final Block block : importer.getBlocks()) {
      final ProtocolSpec protocolSpec = protocolSchedule.getByBlockHeader2(block.getHeader());
      final BlockImporter blockImporter = protocolSpec.getBlockImporter();
      blockImporter.importBlock(context, block, HeaderValidationMode.FULL);
    }
    this.blockchainQueries = new BlockchainQueries(blockchain, stateArchive);
  }

  protected MergeContext createConsensusContext(
      final Blockchain blockchain,
      final GenesisConfigOptions genesisConfigOptions) {
    final OptionalLong terminalBlockNumber = genesisConfigOptions.getTerminalBlockNumber();
    final Optional<Hash> terminalBlockHash = genesisConfigOptions.getTerminalBlockHash();
    final boolean isPostMergeAtGenesis =
        genesisConfigOptions.getTerminalTotalDifficulty().isPresent()
            && genesisConfigOptions.getTerminalTotalDifficulty().get().isZero()
            && blockchain.getGenesisBlockHeader().getDifficulty().isZero();

    final MergeContext mergeContext =
        PostMergeContext.get()
//            .setSyncState(new SyncState(blockchain, null))
            .setTerminalTotalDifficulty(
                genesisConfigOptions
                    .getTerminalTotalDifficulty()
                    .map(Difficulty::of)
                    .orElse(Difficulty.ZERO))
            .setPostMergeAtGenesis(isPostMergeAtGenesis);

    blockchain
        .getFinalized()
        .flatMap(blockchain::getBlockHeader)
        .ifPresent(mergeContext::setFinalized);

    blockchain
        .getSafeBlock()
        .flatMap(blockchain::getBlockHeader)
        .ifPresent(mergeContext::setSafeBlock);

    if (terminalBlockNumber.isPresent() && terminalBlockHash.isPresent()) {
      Optional<BlockHeader> termBlock = blockchain.getBlockHeader(terminalBlockNumber.getAsLong());
      mergeContext.setTerminalPoWBlock(termBlock);
    }
    blockchain.observeBlockAdded(
        blockAddedEvent ->
            blockchain
                .getTotalDifficultyByHash(blockAddedEvent.getBlock().getHeader().getHash())
                .ifPresent(mergeContext::setIsPostMerge));

    return mergeContext;
  }

  public JsonRpcTestMethodsFactory(
      final BlockchainImporter importer,
      final MutableBlockchain blockchain,
      final WorldStateArchive stateArchive,
      final ProtocolContext context) {
    this.importer = importer;
    this.blockchain = blockchain;
    this.stateArchive = stateArchive;
    this.context = context;
    this.blockchainQueries = new BlockchainQueries(blockchain, stateArchive);
    this.synchronizer = mock(Synchronizer.class);
  }

  public JsonRpcTestMethodsFactory(
      final BlockchainImporter importer,
      final MutableBlockchain blockchain,
      final WorldStateArchive stateArchive,
      final ProtocolContext context,
      final Synchronizer synchronizer) {
    this.importer = importer;
    this.blockchain = blockchain;
    this.stateArchive = stateArchive;
    this.context = context;
    this.synchronizer = synchronizer;
    this.blockchainQueries = new BlockchainQueries(blockchain, stateArchive);
  }

  public BlockchainQueries getBlockchainQueries() {
    return blockchainQueries;
  }

  public WorldStateArchive getStateArchive() {
    return stateArchive;
  }

  public Map<String, JsonRpcMethod> methods() {
    final P2PNetwork peerDiscovery = mock(P2PNetwork.class);
    final EthPeers ethPeers = mock(EthPeers.class);
    final TransactionPool transactionPool = mock(TransactionPool.class);
    final PoWMiningCoordinator miningCoordinator = mock(PoWMiningCoordinator.class);
    final ObservableMetricsSystem metricsSystem = new NoOpMetricsSystem();
    final Optional<AccountLocalConfigPermissioningController> accountWhitelistController =
        Optional.of(mock(AccountLocalConfigPermissioningController.class));
    final Optional<NodeLocalConfigPermissioningController> nodeWhitelistController =
        Optional.of(mock(NodeLocalConfigPermissioningController.class));
    final PrivacyParameters privacyParameters = mock(PrivacyParameters.class);

    final FilterManager filterManager =
        new FilterManagerBuilder()
            .blockchainQueries(blockchainQueries)
            .transactionPool(transactionPool)
            .privacyParameters(privacyParameters)
            .build();

    final JsonRpcConfiguration jsonRpcConfiguration = mock(JsonRpcConfiguration.class);
    final WebSocketConfiguration webSocketConfiguration = mock(WebSocketConfiguration.class);
    final MetricsConfiguration metricsConfiguration = mock(MetricsConfiguration.class);
    final NatService natService = new NatService(Optional.empty());

    final List<String> apis = new ArrayList<>();
    apis.add(RpcApis.ETH.name());
    apis.add(RpcApis.NET.name());
    apis.add(RpcApis.WEB3.name());
    apis.add(RpcApis.PRIV.name());
    apis.add(RpcApis.DEBUG.name());

    final Path dataDir = mock(Path.class);

    return new JsonRpcMethodsFactory()
        .methods(
            CLIENT_VERSION,
            NETWORK_ID,
            new StubGenesisConfigOptions(),
            peerDiscovery,
            blockchainQueries,
            synchronizer,
            importer.getProtocolSchedule(),
            context,
            filterManager,
            transactionPool,
            miningCoordinator,
            metricsSystem,
            new HashSet<>(),
            accountWhitelistController,
            nodeWhitelistController,
            apis,
            privacyParameters,
            jsonRpcConfiguration,
            webSocketConfiguration,
            metricsConfiguration,
            natService,
            new HashMap<>(),
            dataDir,
            ethPeers,
            Vertx.vertx(new VertxOptions().setWorkerPoolSize(1)),
            Optional.empty());
  }
}
