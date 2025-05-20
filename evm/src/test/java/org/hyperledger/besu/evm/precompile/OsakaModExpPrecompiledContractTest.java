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
package org.hyperledger.besu.evm.precompile;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.BerlinGasCalculator;
import org.hyperledger.besu.evm.gascalculator.OsakaGasCalculator;

import java.util.Optional;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

/** Tests for the Osaka ModExp precompiled contract that implements EIP-7823. */
class OsakaModExpPrecompiledContractTest {
  @Mock private MessageFrame messageFrame;

  private final OsakaModExpPrecompiledContract osakaContract =
      new OsakaModExpPrecompiledContract(new OsakaGasCalculator());

  private final BigIntegerModularExponentiationPrecompiledContract berlinContract =
      new BigIntegerModularExponentiationPrecompiledContract(new BerlinGasCalculator());

  static Stream<Arguments> validParameters() {
    return Stream.of(
        // Valid case: all parameters within limit
        Arguments.of(
            "0000000000000000000000000000000000000000000000000000000000000400"
                + // 1024 bytes
                "0000000000000000000000000000000000000000000000000000000000000400"
                + // 1024 bytes
                "0000000000000000000000000000000000000000000000000000000000000400"
                + // 1024 bytes
                "0000000000000000000000000000000000000000000000000000000000000001" // actual data:
            // just a single
            // byte
            ),
        // Valid case: at the boundary (1024 bytes)
        Arguments.of(
            "0000000000000000000000000000000000000000000000000000000000000400"
                + // 1024 bytes
                "0000000000000000000000000000000000000000000000000000000000000001"
                + // 1 byte
                "0000000000000000000000000000000000000000000000000000000000000001"
                + // 1 byte
                "01" // actual data: just a single byte
            ));
  }

  static Stream<Arguments> invalidParameters() {
    return Stream.of(
        // Invalid case: base exceeds limit (1025 bytes)
        Arguments.of(
            "0000000000000000000000000000000000000000000000000000000000000401"
                + // 1025 bytes
                "0000000000000000000000000000000000000000000000000000000000000001"
                + // 1 byte
                "0000000000000000000000000000000000000000000000000000000000000001"
                + // 1 byte
                "01" // actual data: just a single byte
            ),
        // Invalid case: exponent exceeds limit (1025 bytes)
        Arguments.of(
            "0000000000000000000000000000000000000000000000000000000000000001"
                + // 1 byte
                "0000000000000000000000000000000000000000000000000000000000000401"
                + // 1025 bytes
                "0000000000000000000000000000000000000000000000000000000000000001"
                + // 1 byte
                "01" // actual data: just a single byte
            ),
        // Invalid case: modulus exceeds limit (1025 bytes)
        Arguments.of(
            "0000000000000000000000000000000000000000000000000000000000000001"
                + // 1 byte
                "0000000000000000000000000000000000000000000000000000000000000001"
                + // 1 byte
                "0000000000000000000000000000000000000000000000000000000000000401"
                + // 1025 bytes
                "01" // actual data: just a single byte
            ),
        // Invalid case: all parameters exceed limit
        Arguments.of(
            "0000000000000000000000000000000000000000000000000000000000000800"
                + // 2048 bytes
                "0000000000000000000000000000000000000000000000000000000000000800"
                + // 2048 bytes
                "0000000000000000000000000000000000000000000000000000000000000800"
                + // 2048 bytes
                "01" // actual data: just a single byte
            ));
  }

  @ParameterizedTest
  @MethodSource("validParameters")
  void shouldSucceedForValidInputs(final String inputHex) {
    final Bytes input = Bytes.fromHexString(inputHex);

    // Osaka implementation should compute successfully
    final PrecompileContractResult osakaResult =
        osakaContract.computePrecompile(input, messageFrame);
    assertThat(osakaResult.isSuccessful()).isTrue();

    // Berlin implementation should also work for these valid inputs
    final PrecompileContractResult berlinResult =
        berlinContract.computePrecompile(input, messageFrame);
    assertThat(berlinResult.isSuccessful()).isTrue();

    // Both implementations should return the same result
    assertThat(osakaResult.output()).isEqualTo(berlinResult.output());
  }

  @ParameterizedTest
  @MethodSource("invalidParameters")
  void shouldFailForOversizedInputs(final String inputHex) {
    final Bytes input = Bytes.fromHexString(inputHex);

    // Osaka implementation should reject inputs that exceed EIP-7823 limits
    final PrecompileContractResult osakaResult =
        osakaContract.computePrecompile(input, messageFrame);
    assertThat(osakaResult.isSuccessful()).isFalse();
    assertThat(osakaResult.getHaltReason())
        .isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));

    // Berlin implementation should accept them (since it doesn't have EIP-7823 limits)
    final PrecompileContractResult berlinResult =
        berlinContract.computePrecompile(input, messageFrame);
    assertThat(berlinResult.isSuccessful()).isTrue();
  }

  @Test
  void shouldHaveSameGasCalculationAsParent() {
    // Generate test input
    final String inputHex =
        "0000000000000000000000000000000000000000000000000000000000000001"
            + // 1 byte
            "0000000000000000000000000000000000000000000000000000000000000001"
            + // 1 byte
            "0000000000000000000000000000000000000000000000000000000000000001"
            + // 1 byte
            "01"; // actual data: just a single byte

    final Bytes input = Bytes.fromHexString(inputHex);

    // Gas calculation should be the same for both implementations
    // (EIP-7823 doesn't change gas calculation)
    assertThat(osakaContract.gasRequirement(input)).isEqualTo(berlinContract.gasRequirement(input));
  }
}
