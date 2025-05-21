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

import static org.hyperledger.besu.evm.internal.Words.clampedToInt;

import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

import java.util.Optional;
import javax.annotation.Nonnull;

import org.apache.tuweni.bytes.Bytes;

/**
 * The ModExp precompiled contract implementation for Osaka fork that includes EIP-7823: Set upper
 * bounds for MODEXP.
 */
public class OsakaModExpPrecompiledContract
    extends BigIntegerModularExponentiationPrecompiledContract {

  /** Maximum size of each parameter (base, exponent, modulus) in bytes */
  private static final int MAX_LENGTH = 1024; // 8192 bits = 1024 bytes as per EIP-7823

  /** Error result to avoid recreating it for each failure case */
  private static final PrecompileContractResult HALT_RESULT =
      PrecompileContractResult.halt(null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));

  /**
   * Instantiates a new ModExp precompiled contract for Osaka with EIP-7823 limits.
   *
   * @param gasCalculator the gas calculator
   */
  public OsakaModExpPrecompiledContract(final GasCalculator gasCalculator) {
    super(gasCalculator);
  }

  /**
   * Check if the input parameters comply with EIP-7823 limits.
   *
   * @param input the input bytes
   * @return true if all parameters are within limits, false otherwise
   */
  private boolean isWithinLimits(final Bytes input) {
    // Fast path: check input size first - must be at least 96 bytes to contain length
    // specifications
    if (input.size() < BASE_OFFSET) {
      return true; // Not enough bytes to specify lengths, will be handled by parent
    }

    // Optimization: Check for zeros in high-order bytes of the length fields
    // If any length parameter has a non-zero byte in high order (>1024 bytes), it exceeds the limit

    // Each length field is a 32-byte (256-bit) value, but we only care about the top 23 bytes
    // (since 0xFF + 8 most significant bits would be > 1024 = 2^10 bytes)

    // Check base length (bytes 0-22)
    for (int i = 0; i < 23; i++) {
      if (input.get(i) != 0) {
        return false; // Exceeds limit
      }
    }

    // Check exponent length (bytes 32-54)
    for (int i = 32; i < 55; i++) {
      if (input.get(i) != 0) {
        return false; // Exceeds limit
      }
    }

    // Check modulus length (bytes 64-86)
    for (int i = 64; i < 87; i++) {
      if (input.get(i) != 0) {
        return false; // Exceeds limit
      }
    }

    // If we reach this point, we need to check the lower bytes of each length field
    final int baseLength = clampedToInt(baseLength(input));
    final int exponentLength = clampedToInt(exponentLength(input));
    final int modulusLength = clampedToInt(modulusLength(input));

    // Apply EIP-7823 limits - all parameters must be <= 1024 bytes
    return baseLength <= MAX_LENGTH && exponentLength <= MAX_LENGTH && modulusLength <= MAX_LENGTH;
  }

  @Nonnull
  @Override
  public PrecompileContractResult computePrecompile(
      final Bytes input, @Nonnull final MessageFrame messageFrame) {
    // Fast path: check if any parameter exceeds the EIP-7823 limit
    if (!isWithinLimits(input)) {
      return HALT_RESULT;
    }

    // If all parameters are within limits, delegate to the parent implementation
    return super.computePrecompile(input, messageFrame);
  }
}
