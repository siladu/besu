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
package org.hyperledger.besu.ethereum.api.jsonrpc.context;

/**
 * Tracks timing information for an RPC request through its lifecycle.
 *
 * <p>This class captures timestamps at key points in request processing to enable detailed latency
 * breakdown:
 *
 * <ul>
 *   <li>T-2: Request arrived (before body read/decompression)
 *   <li>T-1: Request body received, before JSON parsing
 *   <li>T0: Request parsed and ready for execution
 *   <li>T0.5: Request deserialized (mapTo JsonRpcRequest)
 *   <li>T1: Handler execution starts
 *   <li>T2: Handler execution completes
 *   <li>T3: Response fully flushed to client socket
 * </ul>
 */
public class RpcTimingContext {
  private final String method;
  private final Object requestId;
  private long requestArrivedNs; // T-2 (before body read/decompression)
  private long requestReceivedNs; // T-1 (before JSON parsing)
  private final long requestParsedNs; // T0
  private long requestDeserializedNs; // T0.5 (after mapTo)
  private long handlerStartNs; // T1
  private long handlerEndNs; // T2
  private String metadata; // Optional metadata (e.g., "14 blobs" for engine_getBlobsV2)

  /**
   * Creates a new timing context for an RPC request.
   *
   * @param method the RPC method name (e.g., "engine_getBlobsV2")
   * @param requestId the request ID for correlation in logs
   * @param requestParsedNs timestamp (in nanoseconds) when the request was parsed
   */
  public RpcTimingContext(final String method, final Object requestId, final long requestParsedNs) {
    this.method = method;
    this.requestId = requestId;
    this.requestParsedNs = requestParsedNs;
  }

  /**
   * Gets the RPC method name.
   *
   * @return the method name
   */
  public String getMethod() {
    return method;
  }

  /**
   * Gets the request ID.
   *
   * @return the request ID
   */
  public Object getRequestId() {
    return requestId;
  }

  /**
   * Gets the timestamp when the request arrived (T-2).
   *
   * @return timestamp in nanoseconds
   */
  public long getRequestArrivedNs() {
    return requestArrivedNs;
  }

  /**
   * Sets the timestamp when the request arrived (T-2).
   *
   * @param requestArrivedNs timestamp in nanoseconds
   */
  public void setRequestArrivedNs(final long requestArrivedNs) {
    this.requestArrivedNs = requestArrivedNs;
  }

  /**
   * Gets the timestamp when the request body was received (T-1).
   *
   * @return timestamp in nanoseconds
   */
  public long getRequestReceivedNs() {
    return requestReceivedNs;
  }

  /**
   * Sets the timestamp when the request body was received (T-1).
   *
   * @param requestReceivedNs timestamp in nanoseconds
   */
  public void setRequestReceivedNs(final long requestReceivedNs) {
    this.requestReceivedNs = requestReceivedNs;
  }

  /**
   * Gets the timestamp when the request was parsed (T0).
   *
   * @return timestamp in nanoseconds
   */
  public long getRequestParsedNs() {
    return requestParsedNs;
  }

  /**
   * Gets the timestamp when the request was deserialized to JsonRpcRequest (T0.5).
   *
   * @return timestamp in nanoseconds
   */
  public long getRequestDeserializedNs() {
    return requestDeserializedNs;
  }

  /**
   * Sets the timestamp when the request was deserialized to JsonRpcRequest (T0.5).
   *
   * @param requestDeserializedNs timestamp in nanoseconds
   */
  public void setRequestDeserializedNs(final long requestDeserializedNs) {
    this.requestDeserializedNs = requestDeserializedNs;
  }

  /**
   * Gets the timestamp when the handler started (T1).
   *
   * @return timestamp in nanoseconds
   */
  public long getHandlerStartNs() {
    return handlerStartNs;
  }

  /**
   * Sets the timestamp when the handler started (T1).
   *
   * @param handlerStartNs timestamp in nanoseconds
   */
  public void setHandlerStartNs(final long handlerStartNs) {
    this.handlerStartNs = handlerStartNs;
  }

  /**
   * Gets the timestamp when the handler completed (T2).
   *
   * @return timestamp in nanoseconds
   */
  public long getHandlerEndNs() {
    return handlerEndNs;
  }

  /**
   * Sets the timestamp when the handler completed (T2).
   *
   * @param handlerEndNs timestamp in nanoseconds
   */
  public void setHandlerEndNs(final long handlerEndNs) {
    this.handlerEndNs = handlerEndNs;
  }

  /**
   * Gets optional metadata about the request.
   *
   * @return metadata string (e.g., "14 blobs") or null
   */
  public String getMetadata() {
    return metadata;
  }

  /**
   * Sets optional metadata about the request.
   *
   * @param metadata metadata string (e.g., "14 blobs")
   */
  public void setMetadata(final String metadata) {
    this.metadata = metadata;
  }

  /**
   * Calculates the total elapsed time from request parsing to the given timestamp.
   *
   * @param currentNs the current timestamp in nanoseconds
   * @return elapsed time in milliseconds
   */
  public double getTotalMs(final long currentNs) {
    return (currentNs - requestParsedNs) / 1_000_000.0;
  }
}
