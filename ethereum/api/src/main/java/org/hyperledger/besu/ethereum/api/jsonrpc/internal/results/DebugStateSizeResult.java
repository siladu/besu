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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results;

import com.fasterxml.jackson.annotation.JsonGetter;

/** Result object for debug_stateSize RPC method. */
public class DebugStateSizeResult implements JsonRpcResult {

  private final String blockNumber;
  private final String stateRoot;
  private final String accounts;
  private final String accountBytes;
  private final String contractCodes;
  private final String contractCodeBytes;
  private final String storages;
  private final String storageBytes;
  private final String trieNodes;
  private final String trieNodeBytes;

  public DebugStateSizeResult(
      final long blockNumber,
      final String stateRoot,
      final long accounts,
      final long accountBytes,
      final long contractCodes,
      final long contractCodeBytes,
      final long storages,
      final long storageBytes,
      final long trieNodes,
      final long trieNodeBytes) {
    this.blockNumber = Quantity.create(blockNumber);
    this.stateRoot = stateRoot;
    this.accounts = String.valueOf(accounts);
    this.accountBytes = String.valueOf(accountBytes);
    this.contractCodes = String.valueOf(contractCodes);
    this.contractCodeBytes = String.valueOf(contractCodeBytes);
    this.storages = String.valueOf(storages);
    this.storageBytes = String.valueOf(storageBytes);
    this.trieNodes = String.valueOf(trieNodes);
    this.trieNodeBytes = String.valueOf(trieNodeBytes);
  }

  @JsonGetter("blockNumber")
  public String getBlockNumber() {
    return blockNumber;
  }

  @JsonGetter("stateRoot")
  public String getStateRoot() {
    return stateRoot;
  }

  @JsonGetter("accounts")
  public String getAccounts() {
    return accounts;
  }

  @JsonGetter("accountBytes")
  public String getAccountBytes() {
    return accountBytes;
  }

  @JsonGetter("contractCodes")
  public String getContractCodes() {
    return contractCodes;
  }

  @JsonGetter("contractCodeBytes")
  public String getContractCodeBytes() {
    return contractCodeBytes;
  }

  @JsonGetter("storages")
  public String getStorages() {
    return storages;
  }

  @JsonGetter("storageBytes")
  public String getStorageBytes() {
    return storageBytes;
  }

  @JsonGetter("trieNodes")
  public String getTrieNodes() {
    return trieNodes;
  }

  @JsonGetter("trieNodeBytes")
  public String getTrieNodeBytes() {
    return trieNodeBytes;
  }
}
