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
package org.hyperledger.besu.ethereum.eth.transactions;

import org.hyperledger.besu.ethereum.core.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A lock-free hand-off queue between transaction intake and block building, used when the
 * transaction pool is bypassed ({@code --Xtx-pool-bypass}). Producers (RPC worker threads) append
 * validated transactions in submission order, which per sender must be nonce order; the block
 * builder is the single consumer, draining up to a gas budget. Transactions that fail execution
 * during block building are dropped, not re-queued; duplicates and nonce replays are rejected by
 * execution.
 */
public class SelectionFeed {

  private final ConcurrentLinkedQueue<Transaction> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();

  public void offerAll(final List<Transaction> transactions) {
    queue.addAll(transactions);
    size.addAndGet(transactions.size());
  }

  /**
   * Drains transactions whose cumulative gas limit fits within the given budget. Must only be
   * called by the single block-building consumer.
   *
   * @param gasBudget the gas budget, typically the block gas limit
   * @return the drained transactions, in submission order
   */
  public List<Transaction> drain(final long gasBudget) {
    final List<Transaction> drained = new ArrayList<>();
    long cumulativeGas = 0;
    Transaction next;
    while ((next = queue.peek()) != null) {
      if (cumulativeGas + next.getGasLimit() > gasBudget) {
        break;
      }
      queue.poll();
      size.decrementAndGet();
      cumulativeGas += next.getGasLimit();
      drained.add(next);
    }
    return drained;
  }

  /** Re-appends drained transactions that were not selected but are still valid (block full). */
  public void reoffer(final List<Transaction> transactions) {
    offerAll(transactions);
  }

  public int size() {
    return size.get();
  }
}
