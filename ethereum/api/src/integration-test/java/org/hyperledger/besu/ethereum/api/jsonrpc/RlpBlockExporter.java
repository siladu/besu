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
package org.hyperledger.besu.ethereum.api.jsonrpc;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.rlp.RLP;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** The Rlp block exporter. */
public class RlpBlockExporter extends BlockExporter {

  public RlpBlockExporter(final List<Block> blockchain) {
    super(blockchain);
  }

  @Override
  protected void exportBlock(final FileOutputStream outputStream, final Block block)
      throws IOException {
    final Bytes rlp = RLP.encode(block::writeTo);
    outputStream.write(rlp.toArrayUnsafe());
  }
}
