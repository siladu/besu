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
package org.hyperledger.besu.ethereum.vm.operations;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.EqOperation;
import org.hyperledger.besu.evm.operation.Operation;

import org.apache.tuweni.bytes.Bytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

public class EqOperationBenchmark extends BinaryOperationBenchmark {

  @Override
  protected Operation.OperationResult invoke(final MessageFrame frame) {
    return EqOperation.staticOperation(frame);
  }

  private static final Bytes ONE = Bytes.fromHexString("0x01");
  private static final Bytes INPUT_2 =
      Bytes.fromHexString("0xfffffffffffffffffffffffffffffffffffffffe");
  private static final Bytes ALL_BITS =
      Bytes.fromHexString("0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

  @Benchmark
  public void worstCaseInputEmpty(final Blackhole blackhole) {
    frame.pushStackItem(Bytes.EMPTY);
    frame.pushStackItem(Bytes.EMPTY);

    blackhole.consume(EqOperation.staticOperation(frame));

    frame.popStackItem();
  }

  @Benchmark
  public void worstCaseInput1(final Blackhole blackhole) {
    frame.pushStackItem(ONE);
    frame.pushStackItem(ONE);

    blackhole.consume(EqOperation.staticOperation(frame));

    frame.popStackItem();
  }

  @Benchmark
  public void worstCaseInput2(final Blackhole blackhole) {
    frame.pushStackItem(INPUT_2);
    frame.pushStackItem(INPUT_2);

    blackhole.consume(EqOperation.staticOperation(frame));

    frame.popStackItem();
  }

  @Benchmark
  public void worstCaseInputNotEqual(final Blackhole blackhole) {
    frame.pushStackItem(ALL_BITS);
    frame.pushStackItem(Bytes.EMPTY);

    blackhole.consume(EqOperation.staticOperation(frame));

    frame.popStackItem();
  }
}
