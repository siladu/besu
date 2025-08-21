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
package org.hyperledger.besu.evm.operation;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class ModOperationTest {

  @Test
  void testModDividendLtDivisor() {
    final MessageFrame frame = createMessageFrame();

    Bytes value0 = Bytes.fromHexString("0x00000000000003e8");
    Bytes value1 = Bytes.fromHexString("0x001fff");

    frame.pushStackItem(value1);
    frame.pushStackItem(value0);

    Operation.OperationResult result = ModOperation.staticOperation(frame);
    assertThat(result.getGasCost()).isEqualTo(5);
    assertThat(frame.popStackItem())
        .isEqualTo(
            Bytes.fromHexString(
                "0x00000000000000000000000000000000000000000000000000000000000003e8"));
  }

  @Test
  void testModDividendLtDivisorOpt() {
    final MessageFrame frame = createMessageFrame();

    Bytes value0 = Bytes.fromHexString("0x00000000000003e8");
    Bytes value1 = Bytes.fromHexString("0x001fff");

    frame.pushStackItem(value1);
    frame.pushStackItem(value0);

    Operation.OperationResult result = ModOperationOpt.staticOperation(frame);
    assertThat(result.getGasCost()).isEqualTo(5);
    assertThat(frame.popStackItem())
        .isEqualTo(
            Bytes.fromHexString(
                "0x00000000000000000000000000000000000000000000000000000000000003e8"));
  }

  @Test
  void testModZeroDivisor() {
    final MessageFrame frame = createMessageFrame();

    Bytes value0 = Bytes32.ZERO;
    Bytes value1 = Bytes.fromHexString("0x001fff");

    frame.pushStackItem(value1);
    frame.pushStackItem(value0);

    Operation.OperationResult result = ModOperation.staticOperation(frame);
    assertThat(result.getGasCost()).isEqualTo(5);
    assertThat(frame.popStackItem()).isEqualTo(Bytes32.ZERO);
  }

  @Test
  void testModZeroDivisorOpt() {
    final MessageFrame frame = createMessageFrame();

    Bytes value0 = Bytes32.ZERO;
    Bytes value1 = Bytes.fromHexString("0x001fff");

    frame.pushStackItem(value1);
    frame.pushStackItem(value0);

    Operation.OperationResult result = ModOperationOpt.staticOperation(frame);
    assertThat(result.getGasCost()).isEqualTo(5);
    assertThat(frame.popStackItem()).isEqualTo(Bytes32.ZERO);
  }

  @Test
  void testMod64BitFastPath() {
    final MessageFrame frame = createMessageFrame();

    Bytes value0 = Bytes.fromHexString("0xfffffffffffffffe");
    Bytes value1 = Bytes.fromHexString("0x001fff");

    frame.pushStackItem(value1);
    frame.pushStackItem(value0);

    Operation.OperationResult result = ModOperation.staticOperation(frame);
    assertThat(result.getGasCost()).isEqualTo(5);
    assertThat(frame.popStackItem())
        .isEqualTo(
            Bytes.fromHexString(
                "0x0000000000000000000000000000000000000000000000000000000000000ffe"));
  }

  @Test
  void testMod64BitFastPathOpt() {
    final MessageFrame frame = createMessageFrame();

    Bytes value0 = Bytes.fromHexString("0xfffffffffffffffe");
    Bytes value1 = Bytes.fromHexString("0x001fff");

    frame.pushStackItem(value1);
    frame.pushStackItem(value0);

    Operation.OperationResult result = ModOperationOpt.staticOperation(frame);
    assertThat(result.getGasCost()).isEqualTo(5);
    assertThat(frame.popStackItem())
        .isEqualTo(
            Bytes.fromHexString(
                "0x0000000000000000000000000000000000000000000000000000000000000ffe"));
  }

  public static MessageFrame createMessageFrame() {
    return MessageFrame.builder()
        .worldUpdater(mock(WorldUpdater.class))
        .originator(Address.ZERO)
        .gasPrice(Wei.ONE)
        .blobGasPrice(Wei.ONE)
        .blockValues(mock(BlockValues.class))
        .miningBeneficiary(Address.ZERO)
        .blockHashLookup((__, ___) -> Hash.ZERO)
        .type(MessageFrame.Type.MESSAGE_CALL)
        .initialGas(1_000_000)
        .address(Address.ZERO)
        .contract(Address.ZERO)
        .inputData(Bytes32.ZERO)
        .sender(Address.ZERO)
        .value(Wei.ZERO)
        .apparentValue(Wei.ZERO)
        .code(CodeV0.EMPTY_CODE)
        .completer(__ -> {})
        .build();
  }
}
