/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.evm.v2.operation;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * EVM v2 SUB operation using long[] stack representation.
 *
 * <p>Each 256-bit word is stored as four longs: index 0 = most significant 64 bits, index 3 = least
 * significant 64 bits.
 */
public class SubOperationV2 extends AbstractFixedCostOperationV2 {

  private static final OperationResult SUB_SUCCESS =
      new OperationResult(3, null);

  /**
   * Instantiates a new Sub operation.
   *
   * @param gasCalculator the gas calculator
   */
  public SubOperationV2(final GasCalculator gasCalculator) {
    super(0x03, "SUB", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(
      final MessageFrame frame, final EVM evm) {
    return staticOperation(frame);
  }

  /**
   * Execute the SUB opcode on the v2 long[] stack.
   *
   * <p>SUB: stack[top-2] = stack[top-1] - stack[top-2], return top-1.
   *
   * @param frame the message frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    if (!frame.stackHasItemsV2(2)) return UNDERFLOW_RESPONSE;
    long[] stack = frame.stackDataV2();
    int top = frame.stackTopV2();
    final int aOffset = (top - 1) << 2;
    final int bOffset = (top - 2) << 2;

    final UInt256 valueA =
        new UInt256(stack[aOffset], stack[aOffset + 1], stack[aOffset + 2], stack[aOffset + 3]);
    final UInt256 valueB =
        new UInt256(stack[bOffset], stack[bOffset + 1], stack[bOffset + 2], stack[bOffset + 3]);

    final UInt256 r = valueA.sub(valueB);

    stack[bOffset] = r.u3();
    stack[bOffset + 1] = r.u2();
    stack[bOffset + 2] = r.u1();
    stack[bOffset + 3] = r.u0();

    frame.setTopV2(top - 1);
    return SUB_SUCCESS;
  }

  public static OperationResult staticOperationIntCarry(final MessageFrame frame) {
    if (!frame.stackHasItemsV2(2)) return UNDERFLOW_RESPONSE;
    long[] stack = frame.stackDataV2();
    int top = frame.stackTopV2();
    final int aOffset = (top - 1) << 2;
    final int bOffset = (top - 2) << 2;

    final UInt256 valueA =
            new UInt256(stack[aOffset], stack[aOffset + 1], stack[aOffset + 2], stack[aOffset + 3]);
    final UInt256 valueB =
            new UInt256(stack[bOffset], stack[bOffset + 1], stack[bOffset + 2], stack[bOffset + 3]);

    final UInt256 r = valueA.subWithIntCarry(valueB);

    stack[bOffset] = r.u3();
    stack[bOffset + 1] = r.u2();
    stack[bOffset + 2] = r.u1();
    stack[bOffset + 3] = r.u0();

    frame.setTopV2(top - 1);
    return SUB_SUCCESS;
  }

  public static OperationResult staticOperationBranchless(final MessageFrame frame) {
    if (!frame.stackHasItemsV2(2)) return UNDERFLOW_RESPONSE;
    long[] stack = frame.stackDataV2();
    int top = frame.stackTopV2();

    branchlessSub(stack, top);

    frame.setTopV2(top - 1);
    return SUB_SUCCESS;
  }

  private static void branchlessSub(final long[] s, final int top) {
    final int a = (top - 1) << 2; // first operand (top)
    final int b = (top - 2) << 2; // second operand
    // Fast path: both values fit in a single limb (common case)
    if ((s[a] | s[a + 1] | s[a + 2] | s[b] | s[b + 1] | s[b + 2]) == 0) {
      long z = s[a + 3] - s[b + 3];
      // borrow is 0 or 1; negate to get 0 or -1L (0xFFFFFFFFFFFFFFFF) for upper limbs
      long fill = -(((~s[a + 3] & s[b + 3]) | (~(s[a + 3] ^ s[b + 3]) & z)) >>> 63);
      s[b] = fill;
      s[b + 1] = fill;
      s[b + 2] = fill;
      s[b + 3] = z;
      return;
    }
    long a0 = s[a + 3], a1 = s[a + 2], a2 = s[a + 1], a3 = s[a];
    long b0 = s[b + 3], b1 = s[b + 2], b2 = s[b + 1], b3 = s[b];
    // a - b with branchless borrow chain
    long z0 = a0 - b0;
    long w = ((~a0 & b0) | (~(a0 ^ b0) & z0)) >>> 63;
    long t1 = a1 - b1;
    long w1 = ((~a1 & b1) | (~(a1 ^ b1) & t1)) >>> 63;
    long z1 = t1 - w;
    w = w1 | (((~t1 & w) | (~(t1 ^ w) & z1)) >>> 63);
    long t2 = a2 - b2;
    long w2 = ((~a2 & b2) | (~(a2 ^ b2) & t2)) >>> 63;
    long z2 = t2 - w;
    w = w2 | (((~t2 & w) | (~(t2 ^ w) & z2)) >>> 63);
    long z3 = a3 - b3 - w;
    s[b] = z3;
    s[b + 1] = z2;
    s[b + 2] = z1;
    s[b + 3] = z0;
  }

  public static OperationResult staticOperationBytes(final MessageFrame frame) {
    if (!frame.stackHasItemsV2(2)) return UNDERFLOW_RESPONSE;
    long[] stack = frame.stackDataV2();
    int top = frame.stackTopV2();
    final int aOffset = (top - 1) << 2;
    final int bOffset = (top - 2) << 2;

    final UInt256 valueA =
            new UInt256(stack[aOffset], stack[aOffset + 1], stack[aOffset + 2], stack[aOffset + 3]);
    final UInt256 valueB =
            new UInt256(stack[bOffset], stack[bOffset + 1], stack[bOffset + 2], stack[bOffset + 3]);
    byte[] bytesA = valueA.toBytesBE();
    byte[] bytesB = valueB.toBytesBE();

    final UInt256 r = UInt256.fromBytesBE(UInt256.sub(bytesA, bytesB));

    stack[bOffset] = r.u3();
    stack[bOffset + 1] = r.u2();
    stack[bOffset + 2] = r.u1();
    stack[bOffset + 3] = r.u0();

    frame.setTopV2(top - 1);
    return SUB_SUCCESS;
  }
}
