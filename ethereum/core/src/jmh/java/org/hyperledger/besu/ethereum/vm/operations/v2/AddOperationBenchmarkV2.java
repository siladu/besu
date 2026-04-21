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
package org.hyperledger.besu.ethereum.vm.operations.v2;

import org.hyperledger.besu.evm.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.v2.operation.AddOperationV2;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

public class AddOperationBenchmarkV2 extends BinaryOperationBenchmarkV2 {

  @Override
  protected Operation.OperationResult invoke(final MessageFrame frame) {
    return AddOperationV2.staticOperation(frame);
  }

  @Benchmark
  public void fastPathZeroA(final Blackhole blackhole) {
    BenchmarkHelperV2.pushUInt256(frame, bPool[index]);
    BenchmarkHelperV2.pushUInt256(frame, UInt256.ZERO);

    blackhole.consume(invoke(frame));

    frame.setTopV2(frame.stackTopV2() - 1);

    index = (index + 1) % SAMPLE_SIZE;
  }

  @Benchmark
  public void fastPathZeroB(final Blackhole blackhole) {
    BenchmarkHelperV2.pushUInt256(frame, UInt256.ZERO);
    BenchmarkHelperV2.pushUInt256(frame, aPool[index]);

    blackhole.consume(invoke(frame));

    frame.setTopV2(frame.stackTopV2() - 1);

    index = (index + 1) % SAMPLE_SIZE;
  }
}
