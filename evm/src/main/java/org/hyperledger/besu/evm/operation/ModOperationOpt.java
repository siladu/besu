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
package org.hyperledger.besu.evm.operation;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

import java.math.BigInteger;
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** The Mod operation. */
public class ModOperationOpt extends AbstractFixedCostOperation {

  private static final OperationResult modSuccess = new OperationResult(5, null);

  /**
   * Instantiates a new Mod operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ModOperationOpt(final GasCalculator gasCalculator) {
    super(0x06, "MOD", 2, 1, gasCalculator, gasCalculator.getLowTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame);
  }

  /**
   * Performs Mod operation.
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final Bytes dividend = frame.popStackItem();
    final Bytes divisor = frame.popStackItem();

    // Early exit: divisor is zero
    if (divisor.isZero()) {
      frame.pushStackItem(Bytes32.ZERO);
      return modSuccess;
    }

    // Trim leading zeros for more efficient comparison
    final Bytes trimmedDividend = dividend.trimLeadingZeros();
    final Bytes trimmedDivisor = divisor.trimLeadingZeros();

    // Early exit: divisor is one
    if (trimmedDivisor.size() == 1 && trimmedDivisor.get(0) == 1) {
      frame.pushStackItem(Bytes32.ZERO);
      return modSuccess;
    }

    // Early exit: dividend < divisor
    if (compareBytes(trimmedDividend, trimmedDivisor) < 0) {
      frame.pushStackItem(padToBytes32(dividend));
      return modSuccess;
    }

    // Early exit: dividend == divisor
    if (trimmedDividend.equals(trimmedDivisor)) {
      frame.pushStackItem(Bytes32.ZERO);
      return modSuccess;
    }

    // Power of 2 optimization: use bitwise AND instead of modulo
    if (isPowerOfTwo(trimmedDivisor)) {
      // For power of 2, x % y = x & (y - 1)
      Bytes mask = subtractOne(divisor);
      Bytes result = bitwiseAnd(dividend, mask);
      frame.pushStackItem(result);
      return modSuccess;
    }

    // 64-bit fast path: both values fit in a single long AND are positive when interpreted as
    // signed
    // This avoids issues with Java's signed arithmetic on unsigned EVM values
    if (trimmedDividend.size() <= 7 && trimmedDivisor.size() <= 7) {
      // With 7 bytes max, we're guaranteed to be in positive long range
      long dividendLong = bytesToLong(trimmedDividend);
      long divisorLong = bytesToLong(trimmedDivisor);
      long result = dividendLong % divisorLong;
      frame.pushStackItem(longToBytes32(result));
      return modSuccess;
    }

    // Fall back to BigInteger for larger values
    BigInteger b1 = new BigInteger(1, dividend.toArrayUnsafe());
    BigInteger b2 = new BigInteger(1, divisor.toArrayUnsafe());
    final BigInteger result = b1.mod(b2);

    Bytes resultBytes = Bytes.wrap(result.toByteArray());
    if (resultBytes.size() > 32) {
      resultBytes = resultBytes.slice(resultBytes.size() - 32, 32);
    }

    final byte[] padding = new byte[32 - resultBytes.size()];
    Arrays.fill(padding, (byte) 0x00);

    frame.pushStackItem(Bytes.concatenate(Bytes.wrap(padding), resultBytes));
    return modSuccess;
  }

  /** Pad bytes to Bytes32. */
  private static Bytes32 padToBytes32(final Bytes value) {
    if (value.size() == 32) {
      return Bytes32.wrap(value.toArray());
    } else if (value.size() < 32) {
      return Bytes32.leftPad(value);
    } else {
      return Bytes32.wrap(value.slice(value.size() - 32, 32).toArray());
    }
  }

  /**
   * Compare two byte arrays as unsigned integers.
   *
   * @return -1 if a < b, 0 if a == b, 1 if a > b
   */
  private static int compareBytes(final Bytes a, final Bytes b) {
    if (a.size() != b.size()) {
      return Integer.compare(a.size(), b.size());
    }
    for (int i = 0; i < a.size(); i++) {
      int cmp = Integer.compare(Byte.toUnsignedInt(a.get(i)), Byte.toUnsignedInt(b.get(i)));
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }

  /** Convert bytes to long (up to 8 bytes). */
  private static long bytesToLong(final Bytes bytes) {
    long result = 0;
    for (int i = 0; i < bytes.size(); i++) {
      result = (result << 8) | Byte.toUnsignedInt(bytes.get(i));
    }
    return result;
  }

  /** Convert long to Bytes32 using optimized bit manipulation. */
  private static Bytes32 longToBytes32(final long value) {
    if (value == 0) {
      return Bytes32.ZERO;
    }

    // Use Long.numberOfLeadingZeros to find the highest set bit
    int leadingZeros = Long.numberOfLeadingZeros(value);
    int significantBytes = (64 - leadingZeros + 7) / 8;

    byte[] result = new byte[32];
    // Write bytes from right to left
    for (int i = 0; i < significantBytes; i++) {
      result[31 - i] = (byte) (value >> (i * 8));
    }

    return Bytes32.wrap(result);
  }

  /** Check if bytes represent power of 2. */
  private static boolean isPowerOfTwo(final Bytes bytes) {
    if (bytes.isZero()) {
      return false;
    }
    // A number is a power of 2 if it has exactly one bit set
    int bitCount = 0;
    for (int i = 0; i < bytes.size(); i++) {
      bitCount += Integer.bitCount(Byte.toUnsignedInt(bytes.get(i)));
      if (bitCount > 1) {
        return false;
      }
    }
    return bitCount == 1;
  }

  /** Subtract one from bytes value. */
  private static Bytes subtractOne(final Bytes value) {
    byte[] result = new byte[32];

    // Copy value to result array, right-aligned
    int valueSize = value.size();
    for (int i = 0; i < valueSize; i++) {
      result[32 - valueSize + i] = value.get(i);
    }

    boolean borrow = true;
    for (int i = 31; i >= 0 && borrow; i--) {
      if (result[i] != 0) {
        result[i]--;
        borrow = false;
      } else {
        result[i] = (byte) 0xFF;
      }
    }

    return Bytes32.wrap(result);
  }

  /** Perform bitwise AND operation. */
  private static Bytes bitwiseAnd(final Bytes a, final Bytes b) {
    byte[] result = new byte[32];

    // Process from right to left
    int aOffset = a.size() - 1;
    int bOffset = b.size() - 1;

    for (int i = 31; i >= 0; i--) {
      byte aByte = (aOffset >= 0) ? a.get(aOffset--) : 0;
      byte bByte = (bOffset >= 0) ? b.get(bOffset--) : 0;
      result[i] = (byte) (aByte & bByte);
    }

    return Bytes32.wrap(result);
  }
}
