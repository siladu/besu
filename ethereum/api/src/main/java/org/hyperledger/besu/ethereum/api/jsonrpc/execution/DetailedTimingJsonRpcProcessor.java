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
package org.hyperledger.besu.ethereum.api.jsonrpc.execution;

import org.hyperledger.besu.ethereum.api.jsonrpc.context.RpcTimingContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestId;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.plugin.services.metrics.Histogram;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processor that adds detailed timing instrumentation for RPC requests.
 *
 * <p>This processor wraps the RPC processor chain and captures timing at key points:
 *
 * <ul>
 *   <li>T0: Request parsed and ready for execution (captured upstream)
 *   <li>T1: Handler execution starts (captured here)
 *   <li>T2: Handler execution completes (captured here)
 * </ul>
 *
 * <p>For Engine API methods (those starting with "engine_"), this processor records metrics and
 * logs detailed timing breakdowns at DEBUG level, showing queueing delay (T0→T1) and execution
 * time (T1→T2).
 */
public class DetailedTimingJsonRpcProcessor implements JsonRpcProcessor {
  private static final Logger LOG =
      LoggerFactory.getLogger(DetailedTimingJsonRpcProcessor.class);

  private final JsonRpcProcessor rpcProcessor;
  private final LabelledMetric<Histogram> requestToHandlerHistogram;
  private final LabelledMetric<Histogram> handlerExecutionHistogram;

  /**
   * Creates a new detailed timing processor.
   *
   * @param rpcProcessor the processor to wrap
   * @param requestToHandlerHistogram histogram for T0→T1 (queueing delay) metrics
   * @param handlerExecutionHistogram histogram for T1→T2 (handler execution) metrics
   */
  public DetailedTimingJsonRpcProcessor(
      final JsonRpcProcessor rpcProcessor,
      final LabelledMetric<Histogram> requestToHandlerHistogram,
      final LabelledMetric<Histogram> handlerExecutionHistogram) {
    this.rpcProcessor = rpcProcessor;
    this.requestToHandlerHistogram = requestToHandlerHistogram;
    this.handlerExecutionHistogram = handlerExecutionHistogram;
  }

  @Override
  public JsonRpcResponse process(
      final JsonRpcRequestId id,
      final JsonRpcMethod method,
      final Span metricSpan,
      final JsonRpcRequestContext request) {

    final String methodName = method.getName();
    final boolean isEngineMethod = methodName.startsWith("engine_");

    // CAPTURE T1 - Handler start
    final long t1 = System.nanoTime();

    // Get T0 from timing context (if available)
    final RpcTimingContext timingContext = request.getTimingContext();
    if (timingContext != null && isEngineMethod) {
      final long t0 = timingContext.getRequestParsedNs();

      // Calculate and record T0→T1 (queueing delay)
      final double t0t1Ms = (t1 - t0) / 1_000_000.0;
      requestToHandlerHistogram.labels(methodName).observe(t0t1Ms / 1000.0);

      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "[{}] [id={}] Handler start T1, queueing={}ms",
            methodName,
            id.getValue(),
            String.format("%.2f", t0t1Ms));
      }
    }

    // Execute the handler
    final JsonRpcResponse response = rpcProcessor.process(id, method, metricSpan, request);

    // CAPTURE T2 - Handler end
    final long t2 = System.nanoTime();

    if (timingContext != null && isEngineMethod) {
      final double t1t2Ms = (t2 - t1) / 1_000_000.0;

      // Record handler execution time
      handlerExecutionHistogram.labels(methodName).observe(t1t2Ms / 1000.0);

      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "[{}] [id={}] Handler end T2, execution={}ms",
            methodName,
            id.getValue(),
            String.format("%.2f", t1t2Ms));
      }

      // Store T2 in timing context for JsonResponseStreamer to use
      timingContext.setHandlerEndNs(t2);
    }

    return response;
  }
}
