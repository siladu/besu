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
package org.hyperledger.besu.consensus.qbft.jsonrpc.methods;

import org.hyperledger.besu.consensus.common.validator.ValidatorProvider;
import org.hyperledger.besu.consensus.common.validator.VoteType;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class QbftProposeValidatorVote implements JsonRpcMethod {
  private static final Logger LOG = LogManager.getLogger();
  private final ValidatorProvider validatorProvider;

  public QbftProposeValidatorVote(final ValidatorProvider validatorProvider) {
    this.validatorProvider = validatorProvider;
  }

  @Override
  public String getName() {
    return RpcMethod.QBFT_PROPOSE_VALIDATOR_VOTE.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    if (validatorProvider.getVoteProviderAtHead().isPresent()) {
      final Address validatorAddress = requestContext.getRequiredParameter(0, Address.class);
      final Boolean add = requestContext.getRequiredParameter(1, Boolean.class);
      LOG.trace(
          "Received RPC rpcName={} voteType={} address={}",
          getName(),
          add ? VoteType.ADD : VoteType.DROP,
          validatorAddress);

      if (add) {
        validatorProvider.getVoteProviderAtHead().get().authVote(validatorAddress);
      } else {
        validatorProvider.getVoteProviderAtHead().get().dropVote(validatorAddress);
      }
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), true);
    } else {
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(), JsonRpcError.METHOD_NOT_ENABLED);
    }
  }
}
