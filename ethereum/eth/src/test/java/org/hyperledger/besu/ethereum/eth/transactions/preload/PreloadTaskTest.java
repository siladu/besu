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

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.StorageSlotKey;

import java.util.PriorityQueue;

import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

public class PreloadTaskTest {

  private static final Address ADDRESS_1 = Address.fromHexString("0x1");
  private static final Address ADDRESS_2 = Address.fromHexString("0x2");
  private static final StorageSlotKey SLOT_KEY_1 = new StorageSlotKey(UInt256.ONE);

  @Test
  public void shouldCreateAccountPreloadTask() {
    final PreloadTask task =
        new PreloadTask(
            PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 100, System.currentTimeMillis());

    assertThat(task.getType()).isEqualTo(PreloadTask.PreloadType.ACCOUNT);
    assertThat(task.getAddress()).isEqualTo(ADDRESS_1);
    assertThat(task.getSlot()).isNull();
    assertThat(task.getPriority()).isEqualTo(100);
  }

  @Test
  public void shouldCreateStoragePreloadTask() {
    final PreloadTask task =
        new PreloadTask(
            PreloadTask.PreloadType.STORAGE, ADDRESS_1, SLOT_KEY_1, 50, System.currentTimeMillis());

    assertThat(task.getType()).isEqualTo(PreloadTask.PreloadType.STORAGE);
    assertThat(task.getAddress()).isEqualTo(ADDRESS_1);
    assertThat(task.getSlot()).isEqualTo(SLOT_KEY_1);
    assertThat(task.getPriority()).isEqualTo(50);
  }

  @Test
  public void shouldOrderByPriorityDescending() {
    final long now = System.currentTimeMillis();
    final PreloadTask task1 =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 50, now);
    final PreloadTask task2 =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_2, null, 100, now);
    final PreloadTask task3 =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 75, now);

    assertThat(task2.compareTo(task1)).isLessThan(0); // Higher priority comes first
    assertThat(task3.compareTo(task1)).isLessThan(0);
    assertThat(task1.compareTo(task2)).isGreaterThan(0);
  }

  @Test
  public void shouldOrderByTimestampWhenPrioritiesEqual() {
    final PreloadTask task1 =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 100, 1000);
    final PreloadTask task2 =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_2, null, 100, 2000);

    assertThat(task1.compareTo(task2)).isLessThan(0); // Earlier timestamp comes first
    assertThat(task2.compareTo(task1)).isGreaterThan(0);
  }

  @Test
  public void shouldWorkInPriorityQueue() {
    final long now = System.currentTimeMillis();
    final PriorityQueue<PreloadTask> queue = new PriorityQueue<>();

    final PreloadTask lowPriority =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 10, now);
    final PreloadTask highPriority =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_2, null, 100, now);
    final PreloadTask mediumPriority =
        new PreloadTask(PreloadTask.PreloadType.STORAGE, ADDRESS_1, SLOT_KEY_1, 50, now);

    queue.offer(lowPriority);
    queue.offer(highPriority);
    queue.offer(mediumPriority);

    assertThat(queue.poll()).isEqualTo(highPriority);
    assertThat(queue.poll()).isEqualTo(mediumPriority);
    assertThat(queue.poll()).isEqualTo(lowPriority);
  }

  @Test
  public void shouldImplementEqualityCorrectly() {
    final long timestamp = 1000;
    final PreloadTask task1 =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 100, timestamp);
    final PreloadTask task2 =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 100, timestamp);
    final PreloadTask task3 =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_2, null, 100, timestamp);

    assertThat(task1).isEqualTo(task2);
    assertThat(task1).isNotEqualTo(task3);
    assertThat(task1.hashCode()).isEqualTo(task2.hashCode());
  }

  @Test
  public void shouldDistinguishBetweenTypes() {
    final long timestamp = 1000;
    final PreloadTask accountTask =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 100, timestamp);
    final PreloadTask storageTask =
        new PreloadTask(PreloadTask.PreloadType.STORAGE, ADDRESS_1, SLOT_KEY_1, 100, timestamp);

    assertThat(accountTask).isNotEqualTo(storageTask);
  }

  @Test
  public void shouldHaveReadableToString() {
    final PreloadTask task =
        new PreloadTask(PreloadTask.PreloadType.ACCOUNT, ADDRESS_1, null, 100, 1234567890);

    final String toString = task.toString();
    assertThat(toString).contains("ACCOUNT");
    assertThat(toString).contains(ADDRESS_1.toString());
    assertThat(toString).contains("100");
    assertThat(toString).contains("1234567890");
  }
}
