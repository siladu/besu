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

  /**
   * Instantiates a new ModExp precompiled contract for Osaka with EIP-7823 limits.
   *
   * @param gasCalculator the gas calculator
   */
  public OsakaModExpPrecompiledContract(final GasCalculator gasCalculator) {
    super(gasCalculator);
  }

  @Nonnull
  @Override
  public PrecompileContractResult computePrecompile(
      final Bytes input, @Nonnull final MessageFrame messageFrame) {
    // First check if any of the length parameters exceed the EIP-7823 limit
    final int baseLength = clampedToInt(baseLength(input));
    final int exponentLength = clampedToInt(exponentLength(input));
    final int modulusLength = clampedToInt(modulusLength(input));

    // Apply EIP-7823: if any parameter exceeds the limit, return an error
    if (baseLength > MAX_LENGTH || exponentLength > MAX_LENGTH || modulusLength > MAX_LENGTH) {
      return PrecompileContractResult.halt(
          null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
    }

    // Continue with the original implementation
    if (isNative()) {
      return computeNative(input);
    } else {
      return computeDefault(input);
    }
  }

  /**
   * Compute native precompile contract with EIP-7823 limits.
   *
   * @param input the input
   * @return the precompile contract result
   */
  @Override
  public PrecompileContractResult computeNative(final @Nonnull Bytes input) {
    // In the case of native implementation, we need to check the EIP-7823 limits again
    final int baseLength = clampedToInt(baseLength(input));
    final int exponentLength = clampedToInt(exponentLength(input));
    final int modulusLength = clampedToInt(modulusLength(input));

    // Apply EIP-7823: if any parameter exceeds the limit, return an error
    if (baseLength > MAX_LENGTH || exponentLength > MAX_LENGTH || modulusLength > MAX_LENGTH) {
      return PrecompileContractResult.halt(
          null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
    }

    // Continue with parent's native implementation
    return super.computeNative(input);
  }

  /**
   * Compute default precompile contract with EIP-7823 limits.
   *
   * @param input the input
   * @return the precompile contract result
   */
  @Nonnull
  @Override
  public PrecompileContractResult computeDefault(final Bytes input) {
    // In the case of default implementation, we need to check the EIP-7823 limits again
    final int baseLength = clampedToInt(baseLength(input));
    final int exponentLength = clampedToInt(exponentLength(input));
    final int modulusLength = clampedToInt(modulusLength(input));

    // Apply EIP-7823: if any parameter exceeds the limit, return an error
    if (baseLength > MAX_LENGTH || exponentLength > MAX_LENGTH || modulusLength > MAX_LENGTH) {
      return PrecompileContractResult.halt(
          null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
    }

    // Continue with parent's default implementation
    return super.computeDefault(input);
  }
}
