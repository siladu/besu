/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.mainnet.feemarket;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.feemarket.BaseFee;
import org.hyperledger.besu.ethereum.core.feemarket.TransactionPriceCalculator;

import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZeroBaseFeeMarket extends LondonFeeMarket {
  private static final Logger LOG = LoggerFactory.getLogger(ZeroBaseFeeMarket.class);

  public ZeroBaseFeeMarket(final long londonForkBlockNumber) {
    this(londonForkBlockNumber, Optional.empty());
  }

  public ZeroBaseFeeMarket(
      final long londonForkBlockNumber, final Optional<Wei> baseFeePerGasOverride) {
    super(londonForkBlockNumber, baseFeePerGasOverride);
  }

  @Override
  public long getBasefeeMaxChangeDenominator() {
    return DEFAULT_BASEFEE_MAX_CHANGE_DENOMINATOR;
  }

  @Override
  public Wei getInitialBasefee() {
    return baseFeeInitialValue;
  }

  @Override
  public long getSlackCoefficient() {
    // TODO SLD setting this to 0 resulting in division by 0 error
    return DEFAULT_SLACK_COEFFICIENT;
  }

  @Override
  public TransactionPriceCalculator getTransactionPriceCalculator() {
    return txPriceCalculator;
  }

  @Override
  public Wei minTransactionPriceInNextBlock(
      final Transaction transaction, final Supplier<Optional<Wei>> baseFeeSupplier) {
    final Optional<Wei> baseFee = baseFeeSupplier.get();
    Optional<Wei> minBaseFeeInNextBlock =
        baseFee.map(bf -> new BaseFee(this, bf).getMinNextValue());

    return this.getTransactionPriceCalculator().price(transaction, minBaseFeeInNextBlock);
  }

  @Override
  public boolean satisfiesFloorTxCost(final Transaction txn) {
    // ensure effective baseFee is at least above floor
    return txn.getGasPrice()
        .map(Optional::of)
        .orElse(txn.getMaxFeePerGas())
        .filter(fee -> fee.greaterOrEqualThan(baseFeeFloor))
        .isPresent();
  }

  @Override
  public Wei computeBaseFee(
      final long blockNumber,
      final Wei parentBaseFee,
      final long parentBlockGasUsed,
      final long targetGasUsed) {

    final Wei baseFee = Wei.ZERO;

    //    if (londonForkBlockNumber == blockNumber) {
    //      return getInitialBasefee();
    //    }
    //
    //    long gasDelta;
    //    Wei feeDelta, baseFee;
    //    if (parentBlockGasUsed == targetGasUsed) {
    //      return parentBaseFee;
    //    } else if (parentBlockGasUsed > targetGasUsed) {
    //      gasDelta = parentBlockGasUsed - targetGasUsed;
    //      final long denominator = getBasefeeMaxChangeDenominator();
    //      feeDelta =
    //          UInt256s.max(
    //              parentBaseFee.multiply(gasDelta).divide(targetGasUsed).divide(denominator),
    // Wei.ONE);
    //      baseFee = parentBaseFee.add(feeDelta);
    //    } else {
    //      gasDelta = targetGasUsed - parentBlockGasUsed;
    //      final long denominator = getBasefeeMaxChangeDenominator();
    //      feeDelta = parentBaseFee.multiply(gasDelta).divide(targetGasUsed).divide(denominator);
    //      baseFee = parentBaseFee.subtract(feeDelta);
    //    }
    LOG.trace(
        "block #{} parentBaseFee: {} parentGasUsed: {} parentGasTarget: {} baseFee: {}",
        blockNumber,
        parentBaseFee,
        parentBlockGasUsed,
        targetGasUsed,
        baseFee);
    return baseFee;
  }

  @Override
  public boolean isForkBlock(final long blockNumber) {
    // TODO SLD cheat, ensure 0 is always compared with 0 in BaseFeeMarketBlockHeaderGasPriceValidationRule
    // TODO SLD this cheat doesn't work when synching a block with a non-zero base fee (if this is how your network started)
//    return true;
    return londonForkBlockNumber == blockNumber;
  }

  @Override
  public boolean isBeforeForkBlock(final long blockNumber) {
    return londonForkBlockNumber > blockNumber;
  }
}
