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
package org.hyperledger.besu.plugin.services.storage.rocksdb.segmented;

import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.metrics.OperationTimer;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.SnappableKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBTransaction;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBConfiguration;
import org.hyperledger.besu.services.kvstore.SegmentedKeyValueStorageTransactionValidatorDecorator;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.rocksdb.ColumnFamilyDescriptor;

import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optimistic RocksDB Columnar key value storage */
public class OptimisticRocksDBColumnarKeyValueStorage extends RocksDBColumnarKeyValueStorage
    implements SnappableKeyValueStorage {
  private static final Logger LOG =
      LoggerFactory.getLogger(OptimisticRocksDBColumnarKeyValueStorage.class);

  /** System property to enable RocksDB secondary instance for reads */
  public static final String USE_SECONDARY_PROPERTY = "besu.rocksdb.useSecondary";

  private final OptimisticTransactionDB db;
  private final boolean useSecondary;
  private volatile RocksDBSecondaryInstance secondaryInstance;
  private volatile boolean secondaryInitAttempted = false;
  private volatile long lastSyncTime = 0;
  private static final long SYNC_INTERVAL_MS = 100; // Sync at most every 100ms

  /**
   * Instantiates a new Rocks db columnar key value optimistic storage.
   *
   * @param configuration the configuration
   * @param segments the segments
   * @param ignorableSegments the ignorable segments
   * @param metricsSystem the metrics system
   * @param rocksDBMetricsFactory the rocks db metrics factory
   * @throws StorageException the storage exception
   */
  public OptimisticRocksDBColumnarKeyValueStorage(
      final RocksDBConfiguration configuration,
      final List<SegmentIdentifier> segments,
      final List<SegmentIdentifier> ignorableSegments,
      final MetricsSystem metricsSystem,
      final RocksDBMetricsFactory rocksDBMetricsFactory)
      throws StorageException {
    super(configuration, segments, ignorableSegments, metricsSystem, rocksDBMetricsFactory);
    // Store the flag but don't initialize yet - lazy init avoids race with primary's WAL recovery
    this.useSecondary = Boolean.getBoolean(USE_SECONDARY_PROPERTY);
    try {

      db =
          RocksDBOpener.openOptimisticTransactionDBWithWarning(
              options, configuration.getDatabaseDir().toString(), columnDescriptors, columnHandles);
      initMetrics();
      initColumnHandles();

      if (useSecondary) {
        LOG.info(
            "RocksDB secondary instance enabled via system property, will initialize lazily on first read");
      }

    } catch (final RocksDBException e) {
      throw parseRocksDBException(e, segments, ignorableSegments);
    }
  }

  private void initializeSecondaryInstance(
      final RocksDBConfiguration configuration, final List<SegmentIdentifier> segments) {
    try {
      LOG.info("Initializing RocksDB secondary instance for read optimization");

      // Create fresh column descriptors for secondary - don't reuse primary's
      // as RocksDB may modify/take ownership of ColumnFamilyOptions during open
      final List<ColumnFamilyDescriptor> secondaryDescriptors =
          segments.stream()
              .map(segment -> createColumnDescriptor(segment, configuration))
              .collect(Collectors.toList());

      secondaryInstance =
          new RocksDBSecondaryInstance(
              configuration.getDatabaseDir(),
              configuration.getDatabaseDir().resolve("secondary"),
              secondaryDescriptors,
              segments);
      LOG.info("RocksDB secondary instance initialized successfully");
    } catch (final StorageException e) {
      LOG.warn(
          "Failed to initialize RocksDB secondary instance, falling back to primary for reads", e);
      secondaryInstance = null;
    }
  }

  @Override
  RocksDB getDB() {
    return db;
  }

  /**
   * Reads a value from storage, using the secondary instance if available for improved read
   * performance.
   *
   * @param segment the segment to read from
   * @param key the key to look up
   * @return the value if present, or empty if not found
   * @throws StorageException if the read fails
   */
  @Override
  public Optional<byte[]> get(final SegmentIdentifier segment, final byte[] key)
      throws StorageException {
    throwIfClosed();

    try (final OperationTimer.TimingContext ignored = metrics.getReadLatency().startTimer()) {
      // Lazy initialization of secondary instance to avoid race with primary's WAL recovery
      if (useSecondary && !secondaryInitAttempted) {
        synchronized (this) {
          if (!secondaryInitAttempted) {
            secondaryInitAttempted = true;
            initializeSecondaryInstance(configuration, trimmedSegments);
          }
        }
      }

      if (secondaryInstance != null) {
        // Sync secondary with primary periodically to balance freshness vs performance
        final long now = System.currentTimeMillis();
        if (now - lastSyncTime > SYNC_INTERVAL_MS) {
          secondaryInstance.tryCatchUpWithPrimary();
          lastSyncTime = now;
        }
        // Route reads to secondary instance
        return secondaryInstance.get(segment, key);
      }
      // Fall back to primary
      return super.get(segment, key);
    }
  }

  /**
   * Synchronizes the secondary instance with the primary. Call this between blocks to ensure reads
   * see the latest committed data.
   */
  public void syncSecondaryWithPrimary() {
    if (secondaryInstance != null) {
      try {
        secondaryInstance.tryCatchUpWithPrimary();
      } catch (final StorageException e) {
        LOG.warn("Failed to sync secondary instance with primary", e);
      }
    }
  }

  /**
   * Checks if a secondary instance is active for reads.
   *
   * @return true if reads are being routed to a secondary instance
   */
  public boolean isSecondaryInstanceActive() {
    return secondaryInstance != null;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      // Close secondary instance first
      if (secondaryInstance != null) {
        try {
          secondaryInstance.close();
        } catch (final IOException e) {
          LOG.warn("Failed to close secondary instance", e);
        }
      }
      // Then close parent resources
      txOptions.close();
      options.close();
      columnHandlesBySegmentIdentifier.values().stream()
          .map(org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDbSegmentIdentifier::get)
          .forEach(org.rocksdb.ColumnFamilyHandle::close);
      getDB().close();
    }
  }

  /**
   * Start a transaction
   *
   * @return the new transaction started
   * @throws StorageException the storage exception
   */
  @Override
  public SegmentedKeyValueStorageTransaction startTransaction() throws StorageException {
    throwIfClosed();
    final WriteOptions writeOptions = new WriteOptions();
    writeOptions.setIgnoreMissingColumnFamilies(true);
    return new SegmentedKeyValueStorageTransactionValidatorDecorator(
        new RocksDBTransaction(
            this::safeColumnHandle, db.beginTransaction(writeOptions), writeOptions, this.metrics),
        this.closed::get);
  }

  /**
   * Take snapshot RocksDb columnar key value snapshot.
   *
   * @return the RocksDb columnar key value snapshot
   * @throws StorageException the storage exception
   */
  @Override
  public RocksDBColumnarKeyValueSnapshot takeSnapshot() throws StorageException {
    throwIfClosed();
    return new RocksDBColumnarKeyValueSnapshot(
        db, configuration.isReadCacheEnabledForSnapshots(), this::safeColumnHandle, metrics);
  }
}
