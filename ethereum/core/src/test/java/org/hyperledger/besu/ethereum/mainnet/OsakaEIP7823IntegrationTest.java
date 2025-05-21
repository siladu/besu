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
package org.hyperledger.besu.ethereum.mainnet;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.gascalculator.IstanbulGasCalculator;
import org.hyperledger.besu.evm.gascalculator.OsakaGasCalculator;
import org.hyperledger.besu.evm.precompile.BigIntegerModularExponentiationPrecompiledContract;
import org.hyperledger.besu.evm.precompile.OsakaModExpPrecompiledContract;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;
import org.hyperledger.besu.evm.precompile.PrecompiledContract.PrecompileContractResult;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OsakaEIP7823IntegrationTest {
  private static final Bytes OVERSIZED_INPUT =
      Bytes.fromHexString(
          "0000000000000000000000000000000000000000000000000000000000000800"
              + // 2048 bytes (exceeds 1024 limit)
              "0000000000000000000000000000000000000000000000000000000000000001"
              + // 1 byte (within limit)
              "0000000000000000000000000000000000000000000000000000000000000001"
              + // 1 byte (within limit)
              "01" // actual data: just a single byte
          );

  @Mock private MessageFrame messageFrame;
  private PrecompileContractRegistry pragueRegistry;
  private PrecompileContractRegistry osakaRegistry;

  @BeforeEach
  public void setup() {
    // Create Prague registry (before EIP-7823)
    final GasCalculator pragueGasCalculator = new IstanbulGasCalculator();
    pragueRegistry =
        MainnetPrecompiledContractRegistries.prague(
            new PrecompiledContractConfiguration(pragueGasCalculator, new PrivacyParameters()));

    // Create Osaka registry (with EIP-7823)
    final GasCalculator osakaGasCalculator = new OsakaGasCalculator();
    osakaRegistry =
        MainnetPrecompiledContractRegistries.osaka(
            new PrecompiledContractConfiguration(osakaGasCalculator, new PrivacyParameters()));
  }

  @Test
  public void pragueForDoesNotEnforceEIP7823Limits() {
    // Get the ModExp contract from Prague registry
    final PrecompiledContract pragueModExp = pragueRegistry.get(Address.MODEXP);
    assertThat(pragueModExp).isInstanceOf(BigIntegerModularExponentiationPrecompiledContract.class);

    // Prague should accept the oversized input
    final PrecompileContractResult pragueResult =
        pragueModExp.computePrecompile(OVERSIZED_INPUT, messageFrame);
    assertThat(pragueResult.isSuccessful()).isTrue();
  }

  @Test
  public void osakaForkEnforcesEIP7823Limits() {
    // Get the ModExp contract from Osaka registry
    final PrecompiledContract osakaModExp = osakaRegistry.get(Address.MODEXP);
    assertThat(osakaModExp).isInstanceOf(OsakaModExpPrecompiledContract.class);

    // Osaka should reject the oversized input
    final PrecompileContractResult osakaResult =
        osakaModExp.computePrecompile(OVERSIZED_INPUT, messageFrame);
    assertThat(osakaResult.isSuccessful()).isFalse();
    assertThat(osakaResult.getHaltReason())
        .isEqualTo(Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
  }
}
