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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.util.DomainObjectDecodeUtils;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batched transaction submission: accepts an array of raw RLP-encoded transactions, decoding and
 * recovering senders in parallel outside any pool lock. When the pool bypass is enabled the
 * transactions go straight to the block builder's selection feed and the pool is never touched;
 * otherwise they fall back to per-transaction pool adds.
 */
public class EthSendRawTransactions implements JsonRpcMethod {
  private static final Logger LOG = LoggerFactory.getLogger(EthSendRawTransactions.class);

  private final TransactionPool transactionPool;

  public EthSendRawTransactions(final TransactionPool transactionPool) {
    this.transactionPool = transactionPool;
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_SEND_RAW_TRANSACTIONS.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    if (requestContext.getRequest().getParamLength() != 1) {
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(), RpcErrorType.INVALID_PARAM_COUNT);
    }
    final String[] rawTransactions;
    try {
      rawTransactions = requestContext.getRequiredParameter(0, String[].class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid transaction parameters (index 0)", RpcErrorType.INVALID_TRANSACTION_PARAMS, e);
    }

    final List<Transaction> transactions;
    try {
      // decode + ECDSA sender recovery in parallel, before any pool interaction; recovery
      // doubles as signature validation
      transactions =
          Arrays.stream(rawTransactions)
              .parallel()
              .map(
                  raw -> {
                    final Transaction transaction =
                        DomainObjectDecodeUtils.decodeRawTransaction(raw);
                    transaction.getSender();
                    return transaction;
                  })
              .toList();
    } catch (final RuntimeException e) {
      LOG.debug("Failed to decode transaction batch: {}", e.getMessage());
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(), RpcErrorType.INVALID_PARAMS);
    }

    final var maybeFeed = transactionPool.getSelectionFeed();
    if (maybeFeed.isPresent()) {
      maybeFeed.get().offerAll(transactions);
      return new JsonRpcSuccessResponse(
          requestContext.getRequest().getId(),
          transactions.stream().map(tx -> tx.getHash().toString()).toList());
    }

    // no bypass: per-transaction pool adds (functionally equivalent, no batching benefit)
    final List<String> results = new ArrayList<>(transactions.size());
    for (final Transaction transaction : transactions) {
      final var validationResult = transactionPool.addTransactionViaApi(transaction);
      results.add(
          validationResult.isValid()
              ? transaction.getHash().toString()
              : validationResult.getInvalidReason().name());
    }
    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), results);
  }
}
