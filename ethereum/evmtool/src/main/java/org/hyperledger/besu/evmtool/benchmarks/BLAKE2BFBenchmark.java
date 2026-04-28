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
package org.hyperledger.besu.evmtool.benchmarks;

import org.hyperledger.besu.crypto.Blake2bfMessageDigest;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.fluent.EvmSpec;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.SequencedMap;

import org.apache.tuweni.bytes.Bytes;

/** Benchmark BLAKE2BF precompile */
public class BLAKE2BFBenchmark extends BenchmarkExecutor {

  private static final long DEFAULT_TRANSACTION_GAS_LIMIT_CAP_OSAKA = 16_777_216L;
  private static final int RANDOM_SAMPLE_SIZE = 3;

  /**
   * The constructor. Use default math based warmup and interations.
   *
   * @param output where to write the stats.
   * @param config benchmark configurations.
   */
  public BLAKE2BFBenchmark(final PrintStream output, final BenchmarkConfig config) {
    super(MATH_WARMUP, MATH_ITERATIONS, output, config);
  }

  @Override
  public void runBenchmark(final Boolean attemptNative, final String fork) {
    EvmSpecVersion forkVersion = EvmSpecVersion.fromName(fork);

    if (attemptNative != null
        && (!attemptNative || !Blake2bfMessageDigest.Blake2bfDigest.maybeEnableNative())) {
      Blake2bfMessageDigest.Blake2bfDigest.disableNative();
    }

    output.println(
        Blake2bfMessageDigest.Blake2bfDigest.isNative() ? "Native BLAKE2BF" : "Java BLAKE2BF");

    PrecompiledContract contract =
        EvmSpec.evmSpec(forkVersion)
            .getPrecompileContractRegistry()
            .get(Address.BLAKE2B_F_COMPRESSION);

    final SequencedMap<String, Bytes> testCases = new LinkedHashMap<>();
    // 213 bytes = [4 bytes for rounds][64 bytes for h][128 bytes for m][8 bytes for t_0][8 bytes
    // for t_1][1 byte for f]
    testCases.put(
        "Case 1",
        Bytes.fromHexString(
            "0000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001"));
    testCases.put(
        "Case 2",
        Bytes.fromHexString(
            "0000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000"));
    testCases.put(
        "Case 3",
        Bytes.fromHexString(
            "007a120048c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001"));
    final Random random = new Random();
    int randomSampleCount = 0;
    while (randomSampleCount < RANDOM_SAMPLE_SIZE) {
      final byte[] data = new byte[212];
      random.nextBytes(data);
      boolean f = random.nextBoolean();
      Bytes dataf = Bytes.concatenate(Bytes.wrap(data), Bytes.of(f ? (byte) 0x01 : (byte) 0x00));
      long gasCost = contract.gasRequirement(dataf);
      if (gasCost > DEFAULT_TRANSACTION_GAS_LIMIT_CAP_OSAKA) {
        continue;
      }
      randomSampleCount++;
      testCases.put("Random " + randomSampleCount, Bytes.wrap(dataf));
    }
    precompile(testCases, contract, forkVersion);
  }

  @Override
  public boolean isPrecompile() {
    return true;
  }
}
