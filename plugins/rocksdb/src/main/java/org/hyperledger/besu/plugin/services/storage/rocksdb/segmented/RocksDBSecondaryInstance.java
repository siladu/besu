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

import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for a RocksDB secondary instance that provides read-only access to a primary database.
 * Secondary instances can catch up with the primary asynchronously via {@link
 * #tryCatchUpWithPrimary()}.
 *
 * <p>Benefits of secondary instances:
 *
 * <ul>
 *   <li>Dedicated read path with its own block cache
 *   <li>No lock contention with write operations on primary
 *   <li>Can serve reads while primary is being written to
 * </ul>
 */
public class RocksDBSecondaryInstance implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(RocksDBSecondaryInstance.class);

  private final RocksDB secondaryDb;
  private final Map<SegmentIdentifier, ColumnFamilyHandle> columnHandlesBySegment;
  private final List<ColumnFamilyHandle> columnHandles;
  private final List<ColumnFamilyDescriptor> columnDescriptors;
  private final ReadOptions readOptions;
  private final DBOptions dbOptions;
  private final Path secondaryPath;

  /**
   * Opens a RocksDB secondary instance for the given primary database.
   *
   * @param primaryPath the path to the primary database directory
   * @param secondaryPath the path for the secondary instance's info log
   * @param columnDescriptors the column family descriptors (must match primary)
   * @param segments the segment identifiers corresponding to the column descriptors
   * @throws StorageException if the secondary instance cannot be opened
   */
  public RocksDBSecondaryInstance(
      final Path primaryPath,
      final Path secondaryPath,
      final List<ColumnFamilyDescriptor> columnDescriptors,
      final List<SegmentIdentifier> segments)
      throws StorageException {
    this.secondaryPath = secondaryPath;
    this.columnHandles = new ArrayList<>(columnDescriptors.size());
    // Store column descriptors to prevent GC of ColumnFamilyOptions while DB is open
    this.columnDescriptors = columnDescriptors;
    this.readOptions = new ReadOptions().setVerifyChecksums(false);

    try {
      // Create secondary directory if it doesn't exist
      Files.createDirectories(secondaryPath);

      // Configure options for secondary instance
      // Note: max_open_files must be -1 for secondary instances
      // Store as instance field to prevent GC while DB is open
      this.dbOptions =
          new DBOptions()
              .setCreateIfMissing(false)
              .setMaxOpenFiles(-1)
              .setInfoLogLevel(org.rocksdb.InfoLogLevel.WARN_LEVEL);

      LOG.info(
          "Opening RocksDB secondary instance at {} for primary at {}",
          secondaryPath,
          primaryPath);

      secondaryDb =
          RocksDB.openAsSecondary(
              dbOptions,
              primaryPath.toString(),
              secondaryPath.toString(),
              columnDescriptors,
              columnHandles);

      // Build map of segment identifiers to column handles
      columnHandlesBySegment =
          segments.stream()
              .collect(
                  Collectors.toMap(
                      segment -> segment,
                      segment -> {
                        for (int i = 0; i < segments.size(); i++) {
                          if (segments.get(i).equals(segment)) {
                            return columnHandles.get(i);
                          }
                        }
                        throw new IllegalStateException(
                            "Column handle not found for segment " + segment.getName());
                      }));

      // Sync with primary immediately after opening to ensure we have valid state
      // This is required before any reads can be performed
      LOG.info("Syncing secondary instance with primary...");
      secondaryDb.tryCatchUpWithPrimary();
      LOG.info("Secondary instance synced with primary");

      LOG.info("RocksDB secondary instance opened successfully");

    } catch (final RocksDBException | IOException e) {
      throw new StorageException("Failed to open RocksDB secondary instance", e);
    }
  }

  /**
   * Gets a value from the secondary instance.
   *
   * @param segment the segment identifier
   * @param key the key to look up
   * @return the value if present, or empty if not found
   * @throws StorageException if the read fails
   */
  public Optional<byte[]> get(final SegmentIdentifier segment, final byte[] key)
      throws StorageException {
    final ColumnFamilyHandle handle = columnHandlesBySegment.get(segment);
    if (handle == null) {
      throw new StorageException("Unknown segment: " + segment.getName());
    }

    try {
      return Optional.ofNullable(secondaryDb.get(handle, readOptions, key));
    } catch (final RocksDBException e) {
      throw new StorageException("Failed to read from secondary instance", e);
    }
  }

  /**
   * Gets the column family handle for a segment.
   *
   * @param segment the segment identifier
   * @return the column family handle
   */
  public ColumnFamilyHandle getColumnHandle(final SegmentIdentifier segment) {
    return columnHandlesBySegment.get(segment);
  }

  /**
   * Attempts to catch up with the primary database. This method reads the latest data from the
   * primary's manifest and WAL files to make them available for reads on the secondary.
   *
   * <p>This operation is non-blocking and may not catch up completely if the primary is actively
   * being written to.
   *
   * @throws StorageException if catch up fails
   */
  public void tryCatchUpWithPrimary() throws StorageException {
    try {
      secondaryDb.tryCatchUpWithPrimary();
    } catch (final RocksDBException e) {
      throw new StorageException("Failed to catch up secondary with primary", e);
    }
  }

  /**
   * Gets the underlying RocksDB instance.
   *
   * @return the RocksDB secondary instance
   */
  public RocksDB getDB() {
    return secondaryDb;
  }

  @Override
  public void close() throws IOException {
    LOG.info("Closing RocksDB secondary instance at {}", secondaryPath);
    readOptions.close();
    columnHandles.forEach(ColumnFamilyHandle::close);
    secondaryDb.close();
    dbOptions.close();
    // Close column family options from descriptors (if they have options)
    columnDescriptors.forEach(
        descriptor -> {
          if (descriptor.getOptions() != null) {
            descriptor.getOptions().close();
          }
        });
  }
}
