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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.DebugStateSizeResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;

import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

public class DebugStateSizeTest {

  private final BlockchainQueries blockchainQueries = mock(BlockchainQueries.class);
  private final Path dataDir = Path.of("/tmp/test");
  private final DebugStateSize method = new DebugStateSize(blockchainQueries, dataDir);

  @Test
  public void shouldHaveCorrectName() {
    assertThat(method.getName()).isEqualTo("debug_stateSize");
  }

  @Test
  public void resultShouldSerializeCorrectly() throws JsonProcessingException {
    final DebugStateSizeResult result =
        new DebugStateSizeResult(
            12345678L,
            "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            1000L,
            2000L,
            3000L,
            4000L,
            5000L,
            6000L,
            7000L,
            8000L);

    final ObjectMapper mapper = new ObjectMapper();
    final String json = mapper.writeValueAsString(result);

    assertThat(json).contains("\"blockNumber\":\"0xbc614e\"");
    assertThat(json)
        .contains(
            "\"stateRoot\":\"0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef\"");
    assertThat(json).contains("\"accounts\":\"1000\"");
    assertThat(json).contains("\"accountBytes\":\"2000\"");
    assertThat(json).contains("\"contractCodes\":\"3000\"");
    assertThat(json).contains("\"contractCodeBytes\":\"4000\"");
    assertThat(json).contains("\"storages\":\"5000\"");
    assertThat(json).contains("\"storageBytes\":\"6000\"");
    assertThat(json).contains("\"trieNodes\":\"7000\"");
    assertThat(json).contains("\"trieNodeBytes\":\"8000\"");
  }
}
