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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** The Mod operation. */
public class ModOperation extends AbstractFixedCostOperation {

  private static final OperationResult modSuccess = new OperationResult(5, null);

  /**
   * Instantiates a new Mod operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ModOperation(final GasCalculator gasCalculator) {
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

    // Use custom 256-bit modulo implementation
    Bytes32 result = mod256(dividend, divisor);
    frame.pushStackItem(result);
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

  /**
   * Custom 256-bit modulo operation. Based on Knuth's Algorithm D from "The Art of Computer
   * Programming" Vol. 2
   */
  private static Bytes32 mod256(final Bytes dividend, final Bytes divisor) {
    // Convert to 4 longs (little-endian: u0 is least significant)
    long[] dividendLongs = bytesToLongs(dividend);
    long[] divisorLongs = bytesToLongs(divisor);

    // Find the actual length of divisor (number of non-zero longs)
    int divisorLen = 4;
    while (divisorLen > 0 && divisorLongs[divisorLen - 1] == 0) {
      divisorLen--;
    }

    if (divisorLen == 0) {
      // Division by zero - should not reach here due to early exit
      return Bytes32.ZERO;
    }

    // Find the actual length of dividend
    int dividendLen = 4;
    while (dividendLen > 0 && dividendLongs[dividendLen - 1] == 0) {
      dividendLen--;
    }

    // If dividend is smaller than divisor, remainder is dividend
    if (dividendLen < divisorLen
        || (dividendLen == divisorLen
            && compareLongs(dividendLongs, divisorLongs, dividendLen) < 0)) {
      return longsToBytes32(dividendLongs);
    }

    // Perform division and get remainder
    long[] remainder = divRem256(dividendLongs, dividendLen, divisorLongs, divisorLen);
    return longsToBytes32(remainder);
  }

  /**
   * Compare two long arrays as unsigned 256-bit integers.
   *
   * @return -1 if a < b, 0 if a == b, 1 if a > b
   */
  private static int compareLongs(final long[] a, final long[] b, final int len) {
    for (int i = len - 1; i >= 0; i--) {
      int cmp = Long.compareUnsigned(a[i], b[i]);
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }

  /** Convert Bytes to array of 4 longs (little-endian). */
  private static long[] bytesToLongs(final Bytes bytes) {
    long[] result = new long[4];

    int len = Math.min(bytes.size(), 32);
    for (int i = 0; i < len; i++) {
      int longIndex = i / 8;
      int byteIndex = i % 8;
      // Build long from right to left within each 8-byte group
      result[longIndex] |= (long) (bytes.get(bytes.size() - 1 - i) & 0xFF) << (byteIndex * 8);
    }

    return result;
  }

  /** Convert array of 4 longs to Bytes32 (little-endian). */
  private static Bytes32 longsToBytes32(final long[] longs) {
    byte[] result = new byte[32];

    for (int i = 0; i < 4; i++) {
      long value = longs[i];
      for (int j = 0; j < 8; j++) {
        result[31 - (i * 8 + j)] = (byte) (value >> (j * 8));
      }
    }

    return Bytes32.wrap(result);
  }

  /**
   * Division with remainder for 256-bit integers. Returns the remainder after dividing dividend by
   * divisor. Based on Knuth's Algorithm D.
   */
  private static long[] divRem256(
      final long[] dividend, final int dividendLen, final long[] divisor, final int divisorLen) {
    // Handle single-digit divisor case
    if (divisorLen == 1) {
      long divisorLong = divisor[0];
      long remainder = 0;

      // Process from most significant to least significant
      for (int i = dividendLen - 1; i >= 0; i--) {
        // Combine remainder with current digit
        long temp = dividend[i];
        if (remainder != 0) {
          // dividend[i] = quotient (we don't need it for mod)
          // remainder = new remainder
          remainder = divideUnsigned128By64(remainder, temp, divisorLong);
        } else if (Long.compareUnsigned(temp, divisorLong) >= 0) {
          remainder = Long.remainderUnsigned(temp, divisorLong);
        } else {
          remainder = temp;
        }
      }

      long[] result = new long[4];
      result[0] = remainder;
      return result;
    }

    // Multi-digit divisor case
    // Normalization: scale so that divisor's most significant bit is set
    int shift = Long.numberOfLeadingZeros(divisor[divisorLen - 1]);

    // Create normalized copies
    long[] normDividend = new long[dividendLen + 1];
    long[] normDivisor = new long[divisorLen];

    // Normalize divisor
    shiftLeft(divisor, divisorLen, shift, normDivisor);

    // Normalize dividend
    normDividend[dividendLen] = shiftLeft(dividend, dividendLen, shift, normDividend);

    // Main division loop
    for (int j = dividendLen - divisorLen; j >= 0; j--) {
      // Estimate quotient digit
      long qhat = estimateQuotient(normDividend, j + divisorLen, normDivisor, divisorLen);

      // Multiply and subtract
      long borrow = multiplyAndSubtract(normDividend, j, normDivisor, divisorLen, qhat);

      // Correction step
      if (borrow != 0) {
        // Add back
        addBack(normDividend, j, normDivisor, divisorLen);
      }
    }

    // Denormalize remainder
    long[] remainder = new long[4];
    shiftRight(normDividend, divisorLen, shift, remainder);

    return remainder;
  }

  /** Divide 128-bit number by 64-bit number. Returns remainder. */
  private static long divideUnsigned128By64(final long high, final long low, final long divisor) {
    if (high == 0) {
      return Long.remainderUnsigned(low, divisor);
    }

    // Use shift-and-subtract algorithm for 128-bit by 64-bit division
    long remainder = high;
    long tempLow = low;

    // Process bit by bit from high to low
    for (int i = 0; i < 64; i++) {
      // Shift remainder:tempLow left by 1
      long bit = (tempLow >>> 63) & 1;
      remainder = (remainder << 1) | bit;
      tempLow = tempLow << 1;

      // Subtract divisor if possible
      if (Long.compareUnsigned(remainder, divisor) >= 0) {
        remainder -= divisor;
      }
    }

    return remainder;
  }

  /** Shift left array of longs by specified bits. Returns carry out. */
  private static long shiftLeft(final long[] src, final int len, final int bits, final long[] dst) {
    if (bits == 0) {
      System.arraycopy(src, 0, dst, 0, len);
      return 0;
    }

    long carry = 0;
    int rightShift = 64 - bits;

    for (int i = 0; i < len; i++) {
      long temp = src[i];
      dst[i] = (temp << bits) | carry;
      carry = temp >>> rightShift;
    }

    return carry;
  }

  /** Shift right array of longs by specified bits. */
  private static void shiftRight(
      final long[] src, final int len, final int bits, final long[] dst) {
    if (bits == 0) {
      System.arraycopy(src, 0, dst, 0, Math.min(len, dst.length));
      return;
    }

    int leftShift = 64 - bits;

    for (int i = 0; i < len - 1 && i < dst.length; i++) {
      dst[i] = (src[i] >>> bits) | (src[i + 1] << leftShift);
    }

    if (len - 1 < dst.length) {
      dst[len - 1] = src[len - 1] >>> bits;
    }
  }

  /** Estimate quotient digit using first two digits. */
  private static long estimateQuotient(
      final long[] dividend, final int dividendIndex, final long[] divisor, final int divisorLen) {
    long dividendHigh = dividendIndex < dividend.length ? dividend[dividendIndex] : 0;
    long dividendLow = dividend[dividendIndex - 1];
    long divisorHigh = divisor[divisorLen - 1];

    // Handle overflow case
    if (Long.compareUnsigned(dividendHigh, divisorHigh) >= 0) {
      return 0xFFFFFFFFFFFFFFFFL;
    }

    // Simple case: dividend high is 0
    if (dividendHigh == 0) {
      return Long.divideUnsigned(dividendLow, divisorHigh);
    }

    // Estimate quotient using shift and subtract method
    // This avoids the need for 128-bit division
    long qhat = 0;
    long rhat = dividendHigh;

    // Shift divisor to align with dividend
    int shift = Long.numberOfLeadingZeros(divisorHigh) - Long.numberOfLeadingZeros(rhat);
    if (shift > 0) {
      long shiftedDivisor = divisorHigh << shift;
      if (Long.compareUnsigned(rhat, shiftedDivisor) >= 0) {
        qhat = 1L << shift;
        rhat -= shiftedDivisor;
      }
    }

    // Refine estimate
    while (Long.compareUnsigned(rhat, divisorHigh) >= 0) {
      qhat++;
      rhat -= divisorHigh;
    }

    return qhat;
  }

  /** Multiply divisor by quotient digit and subtract from dividend. Returns borrow. */
  private static long multiplyAndSubtract(
      final long[] dividend,
      final int offset,
      final long[] divisor,
      final int len,
      final long qhat) {
    long borrow = 0;
    long carry = 0;

    for (int i = 0; i < len; i++) {
      // Multiply divisor[i] by qhat with carry
      long[] product = multiply64x64(divisor[i], qhat);
      long productLow = product[0] + carry;
      long productHigh = product[1];
      if (Long.compareUnsigned(productLow, carry) < 0) {
        productHigh++;
      }
      carry = productHigh;

      // Subtract with borrow
      long diff = dividend[offset + i] - productLow - borrow;
      borrow = (Long.compareUnsigned(dividend[offset + i], productLow + borrow) < 0) ? 1 : 0;
      dividend[offset + i] = diff;
    }

    // Handle final borrow
    if (offset + len < dividend.length) {
      long diff = dividend[offset + len] - carry - borrow;
      borrow = (Long.compareUnsigned(dividend[offset + len], carry + borrow) < 0) ? 1 : 0;
      dividend[offset + len] = diff;
    }

    return borrow;
  }

  /** Add divisor back to correct for overestimate. */
  private static void addBack(
      final long[] dividend, final int offset, final long[] divisor, final int len) {
    long carry = 0;

    for (int i = 0; i < len; i++) {
      long sum = dividend[offset + i] + divisor[i] + carry;
      carry =
          (Long.compareUnsigned(sum, dividend[offset + i]) < 0
                  || Long.compareUnsigned(sum, divisor[i]) < 0)
              ? 1
              : 0;
      dividend[offset + i] = sum;
    }

    if (offset + len < dividend.length && carry != 0) {
      dividend[offset + len] += carry;
    }
  }

  /** Multiply two 64-bit unsigned integers. Returns 128-bit result as array [low, high]. */
  private static long[] multiply64x64(final long a, final long b) {
    // Split into 32-bit halves
    long aLow = a & 0xFFFFFFFFL;
    long aHigh = a >>> 32;
    long bLow = b & 0xFFFFFFFFL;
    long bHigh = b >>> 32;

    // Four multiplications
    long lowLow = aLow * bLow;
    long lowHigh = aLow * bHigh;
    long highLow = aHigh * bLow;
    long highHigh = aHigh * bHigh;

    // Add cross products
    long mid1 = (lowLow >>> 32) + (lowHigh & 0xFFFFFFFFL);
    long mid2 = mid1 + (highLow & 0xFFFFFFFFL);
    long carry = (Long.compareUnsigned(mid2, mid1) < 0) ? 1 : 0;

    // Low 64 bits
    long low = (lowLow & 0xFFFFFFFFL) | (mid2 << 32);

    // High 64 bits
    long high = highHigh + (lowHigh >>> 32) + (highLow >>> 32) + carry + (mid2 >>> 32);

    return new long[] {low, high};
  }
}
