package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine;

import static org.hyperledger.besu.util.Slf4jLambdaHelper.traceLambda;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResultFactory;
import org.hyperledger.besu.ethereum.chain.Blockchain;

import java.util.Arrays;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EngineGetPayloadBodiesByHashV1 extends ExecutionEngineJsonRpcMethod {
  private static final Logger LOG = LoggerFactory.getLogger(EngineGetPayloadBodiesByHashV1.class);
  private final BlockResultFactory blockResultFactory;

  public EngineGetPayloadBodiesByHashV1(
      final Vertx vertx,
      final ProtocolContext protocolContext,
      final BlockResultFactory blockResultFactory,
      final EngineCallListener engineCallListener) {
    super(vertx, protocolContext, engineCallListener);
    this.blockResultFactory = blockResultFactory;
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_GET_PAYLOAD_BODIES_BY_HASH_V1.getMethodName();
  }

  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext request) {
    engineCallListener.executionEngineCalled();

    final Hash[] blockHashes = request.getRequiredParameter(0, Hash[].class);

    traceLambda(
        LOG, "EngineGetPayloadBodiesByHashV1 parameters: blockHashes {}", () -> blockHashes);

    final Blockchain blockchain = protocolContext.getBlockchain();
    return new JsonRpcSuccessResponse(
        request.getRequest().getId(),
        Arrays.stream(blockHashes)
            .map(
                blockHash ->
                    blockchain
                        .getBlockBody(blockHash)
                        .map(blockResultFactory::payloadBodyCompleteV1)
                        .orElse(null))
            .toArray());
  }
}
