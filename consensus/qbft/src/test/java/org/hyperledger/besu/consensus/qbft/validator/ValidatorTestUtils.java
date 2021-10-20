/*
 * Copyright Hyperledger Besu contributors.
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
package org.hyperledger.besu.consensus.qbft.validator;

import org.hyperledger.besu.config.JsonQbftConfigOptions;
import org.hyperledger.besu.config.QbftConfigOptions;
import org.hyperledger.besu.consensus.common.bft.BftForkSpec;
import org.hyperledger.besu.consensus.qbft.MutableQbftConfigOptions;
import org.hyperledger.besu.datatypes.Address;

import java.util.Optional;

public class ValidatorTestUtils {

  static BftForkSpec<QbftConfigOptions> createContractFork(
      final long block, final Address contractAddress) {
    final MutableQbftConfigOptions qbftConfigOptions =
        new MutableQbftConfigOptions(JsonQbftConfigOptions.DEFAULT);
    qbftConfigOptions.setValidatorContractAddress(Optional.of(contractAddress.toHexString()));
    return new BftForkSpec<>(block, qbftConfigOptions);
  }

  static BftForkSpec<QbftConfigOptions> createBlockFork(final long block) {
    final QbftConfigOptions qbftConfigOptions = JsonQbftConfigOptions.DEFAULT;
    return new BftForkSpec<>(block, qbftConfigOptions);
  }
}
