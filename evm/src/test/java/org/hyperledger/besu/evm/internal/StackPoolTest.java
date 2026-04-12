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
package org.hyperledger.besu.evm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StackPoolTest {

  // Direct access to a fresh pool instance (bypasses ThreadLocal so tests are isolated)
  private StackPool pool;

  @BeforeEach
  void setUp() {
    pool = new StackPool();
  }

  @Test
  void borrowReturnsUsableStack() {
    final OperandStack stack = StackPool.borrow(1024);
    assertThat(stack).isNotNull();
    assertThat(stack.isEmpty()).isTrue();
    stack.push(UInt256.ONE);
    assertThat(stack.size()).isEqualTo(1);
    assertThat(stack.pop()).isEqualTo(UInt256.ONE);
  }

  @Test
  void nonStandardSizeBypassesPool() {
    // Non-1024 sizes should get a fresh stack each time, not a pooled one
    final OperandStack a = StackPool.borrow(512);
    final OperandStack b = StackPool.borrow(512);
    StackPool.release(a, 512);
    // After releasing a non-standard stack, borrowing again should NOT return the same instance
    final OperandStack c = StackPool.borrow(512);
    assertThat(c).isNotSameAs(a);
    StackPool.release(b, 512);
    StackPool.release(c, 512);
  }

  @Test
  void releaseNullIsIgnored() {
    // Null guard in release() must not throw
    StackPool.release(null, 1024);
  }

  @Test
  void releasedStackIsReturnedClean() {
    // Push data, release, borrow again — must see an empty stack
    final OperandStack stack = pool.borrowInternal();
    stack.push(Bytes32.leftPad(UInt256.valueOf(42)));
    stack.push(Bytes32.leftPad(UInt256.valueOf(99)));
    pool.releaseInternal(stack);

    final OperandStack reused = pool.borrowInternal();
    assertThat(reused.isEmpty()).isTrue();
  }

  // TODO(human): Implement the two tests below.
  // These cover the core pool reuse invariant and the adaptive sizing behaviour.

  @Test
  void borrowAfterReleaseReturnsSameInstance() {
    // TODO(human): Borrow a stack from pool.borrowInternal(), release it via
    // pool.releaseInternal(), then borrow again. Assert the second borrow returns
    // the exact same object reference (identity, not equality).
    // This proves the pool is actually reusing allocations rather than creating new ones.
  }

  @Test
  void maintainShrinksPoolAfterLowUsage() {
    // TODO(human): Simulate a quiet period: borrow and immediately release a single stack
    // MAINTENANCE_INTERVAL (256) times, calling pool.maintain() each time outstanding hits 0.
    // Then verify pool.capacity has shrunk toward INITIAL_CAPACITY (16).
    // Hint: pool fields outstanding, peakThisCycle, idleCount, capacity are package-private.
    // You can read the maintain() logic in StackPool.java to understand the EMA formula.
  }
}
