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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.EqOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(value = TimeUnit.NANOSECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
public class EqOperationWorstCaseBenchmark {

  private static final Bytes ONE = Bytes.fromHexString("0x01");
  private static final Bytes INPUT_2 =
      Bytes.fromHexString("0xfffffffffffffffffffffffffffffffffffffffe");
  private static final Bytes ALL_BITS =
      Bytes.fromHexString("0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

  protected MessageFrame frame;

  @Setup
  public void setUp() {
    frame = BenchmarkHelper.createMessageCallFrame();
  }

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
