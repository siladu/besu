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
package org.hyperledger.besu.evm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test suite adapted from holiman/uint256 (go-ethereum's uint256 library). Repository:
 * https://github.com/holiman/uint256
 *
 * <p>Tests uint256 operations against BigInteger for correctness.
 */
public class UInt256HolimanTest {

  private static final BigInteger TWO_TO_256 = BigInteger.ONE.shiftLeft(256);

  //  private static final BigInteger UINT256_MAX = TWO_TO_256.subtract(BigInteger.ONE);

  // Binary test cases from holiman/uint256 shared_test.go
  static Stream<Arguments> provideBinaryTestCases() {
    return Stream.of(
        Arguments.of("0x0", "0x0"),
        Arguments.of("0x1", "0x0"),
        Arguments.of("0x1", "0x767676767676767676000000767676767676"),
        Arguments.of("0x2", "0x0"),
        Arguments.of("0x2", "0x1"),
        Arguments.of(
            "0x12cbafcee8f60f9f3fa308c90fde8d298772ffea667aa6bc109d5c661e7929a5",
            "0xc76f4afb041407a8ea478d65024f5c3dfe1db1a1bb10c5ea8bec314ccf9"),
        Arguments.of("0x10000000000000000", "0x2"),
        Arguments.of("0x7000000000000000", "0x8000000000000000"),
        Arguments.of("0x8000000000000000", "0x8000000000000000"),
        Arguments.of("0x8000000000000001", "0x8000000000000000"),
        Arguments.of("0x80000000000000010000000000000000", "0x80000000000000000000000000000000"),
        Arguments.of("0x80000000000000000000000000000000", "0x80000000000000000000000000000001"),
        Arguments.of("0x478392145435897052", "0x111"),
        Arguments.of(
            "0x767676767676767676000000767676767676",
            "0x2900760076761e00020076760000000076767676000000"),
        Arguments.of("0x12121212121212121212121212121212", "0x232323232323232323"),
        Arguments.of(
            "0xfffff716b61616160b0b0b2b0b0b0becf4bef50a0df4f48b090b2b0bc60a0a00",
            "0xfffff716b61616160b0b0b2b0b230b000008010d0a2b00"),
        Arguments.of(
            "0x50beb1c60141a0000dc2b0b0b0b0b0b410a0a0df4f40b090b2b0bc60a0a00",
            "0x2000110000000d0a300e750a000000090a0a"),
        Arguments.of(
            "0x4b00000b41000b0b0b2b0b0b0b0b0b410a0aeff4f40b090b2b0bc60a0a1000",
            "0x4b00000b41000b0b0b2b0b0b0b0b0b410a0aeff4f40b0a0a"),
        Arguments.of("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "0x7"),
        Arguments.of(
            "0xf6376770abd3a36b20394c5664afef1194c801c3f05e42566f085ed24d002bb0",
            "0xb368d219438b7f3f"),
        Arguments.of("0x0", "0x10900000000000000000000000000000000000000000000000000"),
        Arguments.of(
            "0x77676767676760000000000000001002e000000000000040000000e000000000",
            "0xfffc000000000000767676240000000000002b0576047"),
        Arguments.of(
            "0x767676767676000000000076000000000000005600000000000000000000",
            "0x767676767676000000000076000000760000"),
        Arguments.of(
            "0x8200000000000000000000000000000000000000000000000000000000000000",
            "0x8200000000000000fe000004000000ffff000000fffff700"),
        Arguments.of("0xdac7fff9ffd9e1322626262626262600", "0xd021262626262626"),
        Arguments.of(
            "0x8000000000000001800000000000000080000000000000008000000000000000",
            "0x800000000000000080000000000000008000000000000000"),
        Arguments.of(
            "0xe8e8e8e2000100000009ea02000000000000ff3ffffff80000001000220000",
            "0xe8e8e8e2000100000009ea02000000000000ff3ffffff800000010002280ff"),
        Arguments.of(
            "0xc9700000000000000000023f00c00014ff000000000000000022300805",
            "0xc9700000000000000000023f00c00014ff002c000000000000223108"),
        Arguments.of(
            "0x40000000fd000000db0000000000000000000000000000000000000000000001",
            "0x40000000fd000000db0000000000000000000040000000fd000000db000001"),
        Arguments.of(
            "0x40000000fd000000db0000000000000000000000000000000000000000000001",
            "0x40000000fd000000db0000000000000000000040000000fd000000db0000d3"),
        Arguments.of(
            "0x1f000000000000000000000000000000200000000100000000000000000000",
            "0x100000000ffffffffffffffff0000000000002e000000"),
        Arguments.of(
            "0x7effffff80000000000000000000000000020000440000000000000000000001",
            "0x7effffff800000007effffff800000008000ff0000010000"),
        Arguments.of(
            "0x5fd8fffffffffffffffffffffffffffffc090000ce700004d0c9ffffff000001",
            "0x2ffffffffffffffffffffffffffffffffff000000030000"),
        Arguments.of(
            "0x62d8fffffffffffffffffffffffffffffc18000000000000000000ca00000001",
            "0x2ffffffffffffffffffffffffffffffffff200000000000"),
        Arguments.of(
            "0x7effffff8000000000000000000000000000000000000000d900000000000001",
            "0x7effffff8000000000000000000000000000000000008001"),
        Arguments.of("0x6400aff20ff00200004e7fd1eff08ffca0afd1eff08ffca0a", "0x210000000000000022"),
        Arguments.of("0x6d5adef08547abf7eb", "0x13590cab83b779e708b533b0eef3561483ddeefc841f5"),
        Arguments.of(
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
        Arguments.of(
            "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe",
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
        Arguments.of(
            "0xe8e8e8e2000100000009ea02000000000000ff3ffffff80000001000220000",
            "0xffffffffffffffff7effffff800000007effffff800000008000ff0000010000"),
        Arguments.of("0x1ce97e1ab91a", "0x66aa0a5319bcf5cb4"));
  }

  // Ternary test cases from holiman/uint256 shared_test.go
  static Stream<Arguments> provideTernaryTestCases() {
    return Stream.of(
        Arguments.of("0x0", "0x0", "0x0"),
        Arguments.of("0x1", "0x0", "0x0"),
        Arguments.of("0x1", "0x1", "0x0"),
        Arguments.of(
            "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd",
            "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe",
            "0x0"),
        Arguments.of(
            "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd",
            "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe",
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
        Arguments.of(
            "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd",
            "0x3",
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
        Arguments.of(
            "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
        Arguments.of(
            "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe"),
        Arguments.of(
            "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0x2"),
        Arguments.of(
            "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0x1"),
        Arguments.of(
            "0xffffffffffffffffffffffffffffffff",
            "0xffffffffffffffffffffffffffffffff",
            "0xfffffffffffffffffffffffffffffffe00000000000000000000000000000002"),
        Arguments.of(
            "0xffffffffffffffffffffffffffffffff",
            "0xffffffffffffffffffffffffffffffff",
            "0xfffffffffffffffffffffffffffffffe00000000000000000000000000000001"),
        Arguments.of(
            "0xffffffffffffffffffffffffffff000004020041fffffffffc00000060000020",
            "0xffffffffffffffffffffffffffffffe6000000ffffffe60000febebeffffffff",
            "0xffffffffffffffffffe6000000ffffffe60000febebeffffffffffffffffffff"),
        Arguments.of(
            "0xffffffffffffffffffffffffffffffff00ffffe6ff0000000000000060000020",
            "0xffffffffffffffffffffffffffffffffffe6000000ffff00e60000febebeffff",
            "0xffffffffffffffffffe6000000ffff00e60000fe0000ffff00e60000febebeff"),
        Arguments.of(
            "0xfffffffffffffffffffffffff600000000005af50100bebe000000004a00be0a",
            "0xffffffffffffffffffffffffffffeaffdfd9fffffffffffff5f60000000000ff",
            "0xffffffffffffffffffffffeaffdfd9fffffffffffffff60000000000ffffffff"),
        Arguments.of(
            "0x8000000000000001000000000000000000000000000000000000000000000000",
            "0x800000000000000100000000000000000000000000000000000000000000000b",
            "0x8000000000000000000000000000000000000000000000000000000000000000"),
        Arguments.of(
            "0x8000000000000000000000000000000000000000000000000000000000000000",
            "0x8000000000000001000000000000000000000000000000000000000000000000",
            "0x8000000000000000000000000000000000000000000000000000000000000000"),
        Arguments.of(
            "0x8000000000000000000000000000000000000000000000000000000000000000",
            "0x8000000000000001000000000000000000000000000000000000000000000000",
            "0x8000000000000001000000000000000000000000000000000000000000000000"),
        Arguments.of(
            "0x8000000000000000000000000000000000000000000000000000000000000000",
            "0x8000000000000000000000000000000100000000000000000000000000000000",
            "0x8000000000000000000000000000000000000000000000000000000000000001"),
        Arguments.of(
            "0x1", "0x1", "0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff"),
        Arguments.of(
            "0x1", "0x1", "0x1000000003030303030303030303030303030303030303030303030303030"),
        Arguments.of(
            "0x1", "0x1", "0x4000000000000000130303030303030303030303030303030303030303030"),
        Arguments.of("0x1", "0x1", "0x8000000000000000000000000000000043030303000000000"),
        Arguments.of("0x1", "0x1", "0x8000000000000000000000000000000003030303030303030"));
  }

  // Helper methods

  private static UInt256 fromHex(final String hex) {
    return fromHexString(hex);
  }

  private static UInt256 fromHexString(final String hexString) {
    String hex =
        hexString.startsWith("0x") || hexString.startsWith("0X")
            ? hexString.substring(2)
            : hexString;

    if (hex.isEmpty()) return UInt256.ZERO;

    // Remove leading zeros
    hex = hex.replaceFirst("^0(?!$)", "");

    if (hex.length() > 64) {
      throw new IllegalArgumentException("Hex string exceeds 256 bits");
    }

    try {
      java.math.BigInteger bigInt = new java.math.BigInteger(hex, 16);
      return UInt256ParameterisedTest.fromBigInteger(bigInt);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid hex string: " + hexString, e);
    }
  }

  private static BigInteger fromHexBigInt(final String hex) {
    String cleanHex = hex.startsWith("0x") ? hex.substring(2) : hex;
    return new BigInteger(cleanHex, 16);
  }

  private static BigInteger wrap256(final BigInteger value) {
    return value.mod(TWO_TO_256);
  }

  // Binary operation tests

  @ParameterizedTest
  @MethodSource("provideBinaryTestCases")
  void testAdd(final String hexA, final String hexB) {
    BigInteger a = fromHexBigInt(hexA);
    BigInteger b = fromHexBigInt(hexB);
    BigInteger expected = wrap256(a.add(b));

    UInt256 uint256a = fromHex(hexA);
    UInt256 uint256b = fromHex(hexB);
    UInt256 result = uint256a.add(uint256b);

    assertThat(UInt256ParameterisedTest.toBigInteger(result))
        .as("Add(%s, %s)", hexA, hexB)
        .isEqualTo(expected);

    // Test argument immutability
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256a)).isEqualTo(a);
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256b)).isEqualTo(b);
  }

  @ParameterizedTest
  @MethodSource("provideBinaryTestCases")
  void testMul(final String hexA, final String hexB) {
    BigInteger a = fromHexBigInt(hexA);
    BigInteger b = fromHexBigInt(hexB);
    BigInteger expected = wrap256(a.multiply(b));

    UInt256 uint256a = fromHex(hexA);
    UInt256 uint256b = fromHex(hexB);
    UInt256 result = uint256a.mul(uint256b);

    assertThat(UInt256ParameterisedTest.toBigInteger(result))
        .as("Mul(%s, %s)", hexA, hexB)
        .isEqualTo(expected);

    // Test argument immutability
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256a)).isEqualTo(a);
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256b)).isEqualTo(b);
  }

  @ParameterizedTest
  @MethodSource("provideBinaryTestCases")
  void testMod(final String hexA, final String hexB) {
    BigInteger a = fromHexBigInt(hexA);
    BigInteger b = fromHexBigInt(hexB);

    // EVM-compatible: returns 0 when dividing by 0
    BigInteger expected = b.equals(BigInteger.ZERO) ? BigInteger.ZERO : wrap256(a.mod(b));

    UInt256 uint256a = fromHex(hexA);
    UInt256 uint256b = fromHex(hexB);
    UInt256 result = uint256a.mod(uint256b);

    assertThat(UInt256ParameterisedTest.toBigInteger(result))
        .as("Mod(%s, %s)", hexA, hexB)
        .isEqualTo(expected);

    // Test argument immutability
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256a)).isEqualTo(a);
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256b)).isEqualTo(b);
  }

  @ParameterizedTest
  @MethodSource("provideBinaryTestCases")
  void testSMod(final String hexA, final String hexB) {
    BigInteger a = fromHexBigInt(hexA);
    BigInteger b = fromHexBigInt(hexB);

    // Convert to signed interpretation
    BigInteger signedA = toSigned256(a);
    BigInteger signedB = toSigned256(b);

    // EVM-compatible: returns 0 when dividing by 0
    BigInteger expected;
    if (b.equals(BigInteger.ZERO)) {
      expected = BigInteger.ZERO;
    } else {
      BigInteger result = signedA.abs().mod(signedB.abs());
      if (signedA.signum() < 0) {
        result = result.negate();
      }
      expected = wrap256(result);
    }

    UInt256 uint256a = fromHex(hexA);
    UInt256 uint256b = fromHex(hexB);
    UInt256 result = uint256a.signedMod(uint256b);

    assertThat(UInt256ParameterisedTest.toBigInteger(result))
        .as("SMod(%s, %s)", hexA, hexB)
        .isEqualTo(expected);

    // Test argument immutability
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256a)).isEqualTo(a);
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256b)).isEqualTo(b);
  }

  @ParameterizedTest
  @MethodSource("provideBinaryTestCases")
  void testLsh(final String hexA, final String hexB) {
    BigInteger a = fromHexBigInt(hexA);
    BigInteger b = fromHexBigInt(hexB);

    // Shift amount is masked to 0x1FF (9 bits) to match EVM behavior
    int shift = b.intValue() & 0x1FF;
    BigInteger expected = wrap256(a.shiftLeft(shift));

    UInt256 uint256a = fromHex(hexA);
    UInt256 result = uint256a.shiftLeft(shift);

    assertThat(UInt256ParameterisedTest.toBigInteger(result))
        .as("Lsh(%s, %d)", hexA, shift)
        .isEqualTo(expected);

    // Test argument immutability
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256a)).isEqualTo(a);
  }

  @ParameterizedTest
  @MethodSource("provideBinaryTestCases")
  void testRsh(final String hexA, final String hexB) {
    BigInteger a = fromHexBigInt(hexA);
    BigInteger b = fromHexBigInt(hexB);

    // Shift amount is masked to 0x1FF (9 bits) to match EVM behavior
    int shift = b.intValue() & 0x1FF;
    BigInteger expected = wrap256(a.shiftRight(shift));

    UInt256 uint256a = fromHex(hexA);
    UInt256 result = uint256a.shiftRight(shift);

    assertThat(UInt256ParameterisedTest.toBigInteger(result))
        .as("Rsh(%s, %d)", hexA, shift)
        .isEqualTo(expected);

    // Test argument immutability
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256a)).isEqualTo(a);
  }

  // Ternary operation tests

  @ParameterizedTest
  @MethodSource("provideTernaryTestCases")
  void testAddMod(final String hexA, final String hexB, final String hexM) {
    BigInteger a = fromHexBigInt(hexA);
    BigInteger b = fromHexBigInt(hexB);
    BigInteger m = fromHexBigInt(hexM);

    // EVM-compatible: returns 0 when mod is 0
    BigInteger expected = m.equals(BigInteger.ZERO) ? BigInteger.ZERO : wrap256(a.add(b).mod(m));

    UInt256 uint256a = fromHex(hexA);
    UInt256 uint256b = fromHex(hexB);
    UInt256 uint256m = fromHex(hexM);
    UInt256 result = uint256a.addMod(uint256b, uint256m);

    assertThat(UInt256ParameterisedTest.toBigInteger(result))
        .as("AddMod(%s, %s, %s)", hexA, hexB, hexM)
        .isEqualTo(expected);

    // Test argument immutability
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256a)).isEqualTo(a);
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256b)).isEqualTo(b);
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256m)).isEqualTo(m);
  }

  @ParameterizedTest
  @MethodSource("provideTernaryTestCases")
  void testMulMod(final String hexA, final String hexB, final String hexM) {
    BigInteger a = fromHexBigInt(hexA);
    BigInteger b = fromHexBigInt(hexB);
    BigInteger m = fromHexBigInt(hexM);

    // EVM-compatible: returns 0 when mod is 0
    BigInteger expected =
        m.equals(BigInteger.ZERO) ? BigInteger.ZERO : wrap256(a.multiply(b).mod(m));

    UInt256 uint256a = fromHex(hexA);
    UInt256 uint256b = fromHex(hexB);
    UInt256 uint256m = fromHex(hexM);
    UInt256 result = uint256a.mulMod(uint256b, uint256m);

    assertThat(UInt256ParameterisedTest.toBigInteger(result))
        .as("MulMod(%s, %s, %s)", hexA, hexB, hexM)
        .isEqualTo(expected);

    // Test argument immutability
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256a)).isEqualTo(a);
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256b)).isEqualTo(b);
    assertThat(UInt256ParameterisedTest.toBigInteger(uint256m)).isEqualTo(m);
  }

  // Helper for signed conversion (two's complement)
  private static BigInteger toSigned256(final BigInteger value) {
    if (value.bitLength() < 256) {
      return value;
    }
    BigInteger sign = BigInteger.ONE.shiftLeft(255);
    if (value.compareTo(sign) >= 0) {
      return value.subtract(TWO_TO_256);
    }
    return value;
  }
}
