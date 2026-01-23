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
package org.hyperledger.besu.ethereum.api.handlers;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Handler that captures the earliest possible timestamp when a request arrives.
 *
 * <p>This handler should be added to the router chain BEFORE BodyHandler to capture the time before
 * body read and decompression occurs. The timestamp is stored in the routing context as
 * "rpc_request_arrived_ns" for later use in timing breakdown.
 */
public class RequestTimingHandler {

  private RequestTimingHandler() {}

  /**
   * Creates a handler that captures request arrival timestamp.
   *
   * @return a handler that stores System.nanoTime() in routing context
   */
  public static Handler<RoutingContext> handler() {
    return ctx -> {
      // Capture T-2 - timestamp before body is read/decompressed
      ctx.put("rpc_request_arrived_ns", System.nanoTime());
      ctx.next();
    };
  }
}
