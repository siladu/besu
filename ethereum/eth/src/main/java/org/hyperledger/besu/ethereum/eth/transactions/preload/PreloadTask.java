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
package org.hyperledger.besu.ethereum.eth.transactions.preload;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.StorageSlotKey;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Represents a preload task for caching account state or storage slots. Tasks are prioritized based
 * on the transaction score and ordered for execution.
 */
public class PreloadTask implements Comparable<PreloadTask> {

  /** Type of preload operation */
  public enum PreloadType {
    /** Preload account state (nonce, balance, code hash, storage root) */
    ACCOUNT,
    /** Preload storage slot value */
    STORAGE,
    /** Preload contract bytecode */
    CODE
  }

  private final PreloadType type;
  private final Address address;
  private final StorageSlotKey slot;
  private final int priority;
  private final long timestamp;

  /**
   * Creates a preload task.
   *
   * @param type the type of preload operation
   * @param address the account address
   * @param slot the storage slot key (null for ACCOUNT/CODE types)
   * @param priority the priority for ordering (higher values processed first)
   * @param timestamp the creation timestamp for deduplication
   */
  public PreloadTask(
      final PreloadType type,
      final Address address,
      final StorageSlotKey slot,
      final int priority,
      final long timestamp) {
    this.type = type;
    this.address = address;
    this.slot = slot;
    this.priority = priority;
    this.timestamp = timestamp;
  }

  /**
   * Returns the type of preload operation.
   *
   * @return preload type
   */
  public PreloadType getType() {
    return type;
  }

  /**
   * Returns the account address.
   *
   * @return address
   */
  public Address getAddress() {
    return address;
  }

  /**
   * Returns the storage slot key, or null if not applicable.
   *
   * @return storage slot key or null
   */
  public StorageSlotKey getSlot() {
    return slot;
  }

  /**
   * Returns the priority for ordering.
   *
   * @return priority
   */
  public int getPriority() {
    return priority;
  }

  /**
   * Returns the creation timestamp.
   *
   * @return timestamp in milliseconds
   */
  public long getTimestamp() {
    return timestamp;
  }

  /**
   * Compares this task to another for priority ordering. Higher priority values are processed
   * first. If priorities are equal, earlier timestamps are processed first.
   *
   * @param other the other task
   * @return comparison result
   */
  @Override
  public int compareTo(@Nonnull final PreloadTask other) {
    // Higher priority first
    int priorityCompare = Integer.compare(other.priority, this.priority);
    if (priorityCompare != 0) {
      return priorityCompare;
    }
    // Earlier timestamp first
    return Long.compare(this.timestamp, other.timestamp);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PreloadTask that = (PreloadTask) o;
    return priority == that.priority
        && timestamp == that.timestamp
        && type == that.type
        && Objects.equals(address, that.address)
        && Objects.equals(slot, that.slot);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, address, slot, priority, timestamp);
  }

  @Override
  public String toString() {
    return String.format(
        "PreloadTask{type=%s, address=%s, slot=%s, priority=%d, timestamp=%d}",
        type, address, slot, priority, timestamp);
  }
}
