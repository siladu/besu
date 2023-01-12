/*
 * Copyright Hyperledger Besu Contributors.
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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponseType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResultFactory;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EngineGetPayloadBodiesResultV1;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.vertx.core.Vertx;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class EngineGetPayloadBodiesByRangeV1Test {
  private EngineGetPayloadBodiesByRangeV1 method;
  private static final Vertx vertx = Vertx.vertx();
  private static final BlockResultFactory blockResultFactory = new BlockResultFactory();
  @Mock private ProtocolContext protocolContext;
  @Mock private EngineCallListener engineCallListener;
  @Mock private MutableBlockchain blockchain;

  @Before
  public void before() {
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    this.method =
        new EngineGetPayloadBodiesByRangeV1(
            vertx, protocolContext, blockResultFactory, engineCallListener);
  }

  @Test
  public void shouldReturnExpectedMethodName() {
    assertThat(method.getName()).isEqualTo("engine_getPayloadBodiesByRangeV1");
  }

  @Test
  public void shouldReturnEmptyPayloadBodiesWithZeroRange() {
    final var resp = resp(123, 0);
    final EngineGetPayloadBodiesResultV1 result = fromSuccessResp(resp);
    assertThat(result.getPayloadBodies().isEmpty()).isTrue();
  }

  @Test
  public void shouldReturnPayloadForKnownNumber() {
    final SignatureAlgorithm sig = SignatureAlgorithmFactory.getInstance();
    final Hash blockHash1 = Hash.wrap(Bytes32.random());
    final Hash blockHash2 = Hash.wrap(Bytes32.random());
    final Hash blockHash3 = Hash.wrap(Bytes32.random());
    final BlockBody blockBody1 =
        new BlockBody(
            List.of(new TransactionTestFixture().createTransaction(sig.generateKeyPair())),
            Collections.emptyList());
    final BlockBody blockBody2 =
        new BlockBody(
            List.of(
                new TransactionTestFixture().createTransaction(sig.generateKeyPair()),
                new TransactionTestFixture().createTransaction(sig.generateKeyPair())),
            Collections.emptyList());
    final BlockBody blockBody3 =
        new BlockBody(
            List.of(
                new TransactionTestFixture().createTransaction(sig.generateKeyPair()),
                new TransactionTestFixture().createTransaction(sig.generateKeyPair()),
                new TransactionTestFixture().createTransaction(sig.generateKeyPair())),
            Collections.emptyList());
    when(blockchain.getBlockBody(blockHash1)).thenReturn(Optional.of(blockBody1));
    when(blockchain.getBlockBody(blockHash2)).thenReturn(Optional.of(blockBody2));
    when(blockchain.getBlockBody(blockHash3)).thenReturn(Optional.of(blockBody3));
    when(blockchain.getBlockHashByNumber(123)).thenReturn(Optional.of(blockHash1));
    when(blockchain.getBlockHashByNumber(124)).thenReturn(Optional.of(blockHash2));
    when(blockchain.getBlockHashByNumber(125)).thenReturn(Optional.of(blockHash3));

    final var resp = resp(123, 3);
    final EngineGetPayloadBodiesResultV1 result = fromSuccessResp(resp);
    assertThat(result.getPayloadBodies().size()).isEqualTo(3);
    assertThat(result.getPayloadBodies().get(0).size()).isEqualTo(1);
    assertThat(result.getPayloadBodies().get(1).size()).isEqualTo(2);
    assertThat(result.getPayloadBodies().get(2).size()).isEqualTo(3);
  }

  @Test
  public void shouldReturnNullForUnknownNumber() {
    final var resp = resp(123, 3);
    final EngineGetPayloadBodiesResultV1 result = fromSuccessResp(resp);
    assertThat(result.getPayloadBodies().size()).isEqualTo(3);
    assertThat(result.getPayloadBodies().get(0)).isNull();
    assertThat(result.getPayloadBodies().get(1)).isNull();
    assertThat(result.getPayloadBodies().get(2)).isNull();
  }

  @Test
  public void shouldReturnNullForUnknownNumberAndPayloadForKnownNumber() {
    final SignatureAlgorithm sig = SignatureAlgorithmFactory.getInstance();
    final Hash blockHash1 = Hash.wrap(Bytes32.random());
    final Hash blockHash3 = Hash.wrap(Bytes32.random());
    final BlockBody blockBody1 =
        new BlockBody(
            List.of(new TransactionTestFixture().createTransaction(sig.generateKeyPair())),
            Collections.emptyList());
    final BlockBody blockBody3 =
        new BlockBody(
            List.of(
                new TransactionTestFixture().createTransaction(sig.generateKeyPair()),
                new TransactionTestFixture().createTransaction(sig.generateKeyPair()),
                new TransactionTestFixture().createTransaction(sig.generateKeyPair())),
            Collections.emptyList());
    when(blockchain.getBlockBody(blockHash1)).thenReturn(Optional.of(blockBody1));
    when(blockchain.getBlockBody(blockHash3)).thenReturn(Optional.of(blockBody3));
    when(blockchain.getBlockHashByNumber(123)).thenReturn(Optional.of(blockHash1));
    when(blockchain.getBlockHashByNumber(125)).thenReturn(Optional.of(blockHash3));

    final var resp = resp(123, 3);
    final var result = fromSuccessResp(resp);
    assertThat(result.getPayloadBodies().size()).isEqualTo(3);
    assertThat(result.getPayloadBodies().get(0).size()).isEqualTo(1);
    assertThat(result.getPayloadBodies().get(1)).isNull();
    assertThat(result.getPayloadBodies().get(2).size()).isEqualTo(3);
  }

  private JsonRpcResponse resp(final long startBlockNumber, final long range) {
    return method.response(
        new JsonRpcRequestContext(
            new JsonRpcRequest(
                "2.0",
                RpcMethod.ENGINE_GET_PAYLOAD_BODIES_BY_Range_V1.getMethodName(),
                new Object[] {startBlockNumber, range})));
  }

  private EngineGetPayloadBodiesResultV1 fromSuccessResp(final JsonRpcResponse resp) {
    assertThat(resp.getType()).isEqualTo(JsonRpcResponseType.SUCCESS);
    return Optional.of(resp)
        .map(JsonRpcSuccessResponse.class::cast)
        .map(JsonRpcSuccessResponse::getResult)
        .map(EngineGetPayloadBodiesResultV1.class::cast)
        .get();
  }
}
