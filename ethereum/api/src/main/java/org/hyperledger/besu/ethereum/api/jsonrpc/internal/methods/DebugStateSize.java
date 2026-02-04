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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.DebugStateSizeResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bouncycastle.util.Arrays;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Returns state size statistics for the database. */
public class DebugStateSize implements JsonRpcMethod {

  private static final Logger LOG = LoggerFactory.getLogger(DebugStateSize.class);
  private static final String DATABASE_PATH = "database";

  private static final Set<KeyValueSegmentIdentifier> TARGET_SEGMENTS =
      EnumSet.of(
          KeyValueSegmentIdentifier.ACCOUNT_INFO_STATE,
          KeyValueSegmentIdentifier.ACCOUNT_STORAGE_STORAGE,
          KeyValueSegmentIdentifier.CODE_STORAGE,
          KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE);

  private final BlockchainQueries blockchainQueries;
  private final Path dataDir;

  public DebugStateSize(final BlockchainQueries blockchainQueries, final Path dataDir) {
    this.blockchainQueries = blockchainQueries;
    this.dataDir = dataDir;
  }

  @Override
  public String getName() {
    return RpcMethod.DEBUG_STATE_SIZE.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    final long blockNumber = blockchainQueries.headBlockNumber();
    final Hash stateRoot =
        blockchainQueries
            .getBlockHeaderByNumber(blockNumber)
            .map(header -> header.getStateRoot())
            .orElse(Hash.EMPTY);

    final String dbPath = dataDir.resolve(DATABASE_PATH).toString();

    try {
      final Map<KeyValueSegmentIdentifier, ColumnFamilyStats> stats =
          collectColumnFamilyStats(dbPath);

      final ColumnFamilyStats accountInfo =
          stats.getOrDefault(KeyValueSegmentIdentifier.ACCOUNT_INFO_STATE, ColumnFamilyStats.EMPTY);
      final ColumnFamilyStats accountStorage =
          stats.getOrDefault(
              KeyValueSegmentIdentifier.ACCOUNT_STORAGE_STORAGE, ColumnFamilyStats.EMPTY);
      final ColumnFamilyStats codeStorage =
          stats.getOrDefault(KeyValueSegmentIdentifier.CODE_STORAGE, ColumnFamilyStats.EMPTY);
      final ColumnFamilyStats trieBranch =
          stats.getOrDefault(
              KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE, ColumnFamilyStats.EMPTY);

      return new JsonRpcSuccessResponse(
          requestContext.getRequest().getId(),
          new DebugStateSizeResult(
              blockNumber,
              stateRoot.toString(),
              accountInfo.keys(),
              accountInfo.totalSize(),
              codeStorage.keys(),
              codeStorage.totalSize(),
              accountStorage.keys(),
              accountStorage.totalSize(),
              trieBranch.keys(),
              trieBranch.totalSize()));

    } catch (final RuntimeException e) {
      LOG.error("Failed to collect state size statistics", e);
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(), RpcErrorType.INTERNAL_ERROR);
    }
  }

  private Map<KeyValueSegmentIdentifier, ColumnFamilyStats> collectColumnFamilyStats(
      final String dbPath) {
    final Map<KeyValueSegmentIdentifier, ColumnFamilyStats> result =
        new EnumMap<>(KeyValueSegmentIdentifier.class);

    RocksDB.loadLibrary();

    try (final Options options = new Options()) {
      options.setCreateIfMissing(true);

      final List<byte[]> cfNames = RocksDB.listColumnFamilies(options, dbPath);
      final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
      final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();

      for (final byte[] cfName : cfNames) {
        cfDescriptors.add(new ColumnFamilyDescriptor(cfName));
      }

      try (final RocksDB rocksdb = RocksDB.openReadOnly(dbPath, cfDescriptors, cfHandles)) {
        for (final ColumnFamilyHandle cfHandle : cfHandles) {
          final KeyValueSegmentIdentifier segment = getSegmentById(cfHandle.getName());
          if (segment != null && TARGET_SEGMENTS.contains(segment)) {
            final ColumnFamilyStats stats = getStatsForColumnFamily(rocksdb, cfHandle);
            result.put(segment, stats);
          }
        }
      } finally {
        for (final ColumnFamilyHandle cfHandle : cfHandles) {
          cfHandle.close();
        }
      }
    } catch (final RocksDBException e) {
      throw new RuntimeException("Failed to query RocksDB statistics", e);
    }

    return result;
  }

  private ColumnFamilyStats getStatsForColumnFamily(
      final RocksDB rocksdb, final ColumnFamilyHandle cfHandle) throws RocksDBException {
    final String numKeysStr = rocksdb.getProperty(cfHandle, "rocksdb.estimate-num-keys");
    final String sstSizeStr = rocksdb.getProperty(cfHandle, "rocksdb.total-sst-files-size");
    final String blobSizeStr = rocksdb.getProperty(cfHandle, "rocksdb.total-blob-file-size");

    final long numKeys = parsePropertyValue(numKeysStr);
    final long sstSize = parsePropertyValue(sstSizeStr);
    final long blobSize = parsePropertyValue(blobSizeStr);

    return new ColumnFamilyStats(numKeys, sstSize + blobSize);
  }

  private long parsePropertyValue(final String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Long.parseLong(value);
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  private KeyValueSegmentIdentifier getSegmentById(final byte[] id) {
    for (final KeyValueSegmentIdentifier segment : KeyValueSegmentIdentifier.values()) {
      if (Arrays.areEqual(segment.getId(), id)) {
        return segment;
      }
    }
    return null;
  }

  private record ColumnFamilyStats(long keys, long totalSize) {
    static final ColumnFamilyStats EMPTY = new ColumnFamilyStats(0, 0);
  }
}
