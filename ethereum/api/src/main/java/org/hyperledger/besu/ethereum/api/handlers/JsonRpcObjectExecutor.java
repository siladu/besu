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

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;

import org.hyperledger.besu.ethereum.api.jsonrpc.JsonResponseStreamer;
import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcConfiguration;
import org.hyperledger.besu.ethereum.api.jsonrpc.context.ContextKey;
import org.hyperledger.besu.ethereum.api.jsonrpc.execution.JsonRpcExecutor;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.plugin.services.rpc.RpcResponseType;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.opentelemetry.api.trace.Tracer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class JsonRpcObjectExecutor extends AbstractJsonRpcExecutor {
  private final ObjectWriter jsonObjectWriter = createObjectWriter();

  public JsonRpcObjectExecutor(
      final JsonRpcExecutor jsonRpcExecutor,
      final Tracer tracer,
      final RoutingContext ctx,
      final JsonRpcConfiguration jsonRpcConfiguration) {
    super(jsonRpcExecutor, tracer, ctx, jsonRpcConfiguration);
  }

  @Override
  void execute() throws IOException {
    HttpServerResponse response = ctx.response();
    response = response.putHeader("Content-Type", APPLICATION_JSON);

    final JsonObject jsonRequest = ctx.get(ContextKey.REQUEST_BODY_AS_JSON_OBJECT.name());
    lazyTraceLogger(jsonRequest::toString);
    final JsonRpcResponse jsonRpcResponse =
        executeRequest(jsonRpcExecutor, tracer, jsonRequest, ctx);
    handleJsonObjectResponse(response, jsonRpcResponse, ctx);
  }

  @Override
  String getRpcMethodName(final RoutingContext ctx) {
    final JsonObject jsonObject = ctx.get(ContextKey.REQUEST_BODY_AS_JSON_OBJECT.name());
    return jsonObject.getString("method");
  }

  private void handleJsonObjectResponse(
      final HttpServerResponse response,
      final JsonRpcResponse jsonRpcResponse,
      final RoutingContext ctx)
      throws IOException {
    response.setStatusCode(status(jsonRpcResponse).code());
    if (jsonRpcResponse.getType() == RpcResponseType.NONE) {
      response.end();
    } else {
      // Extract timing context from RoutingContext
      final org.hyperledger.besu.ethereum.api.jsonrpc.context.RpcTimingContext timingContext =
          ctx.get("rpc_timing_context");

      // Add metadata for engine_getBlobsV2 requests
      if (timingContext != null
          && "engine_getBlobsV2".equals(timingContext.getMethod())
          && jsonRpcResponse instanceof org.hyperledger.besu.ethereum.api.jsonrpc.internal.response
              .JsonRpcSuccessResponse) {
        final Object result =
            ((org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse)
                    jsonRpcResponse)
                .getResult();
        if (result instanceof java.util.List) {
          final int blobCount = ((java.util.List<?>) result).size();
          timingContext.setMetadata(blobCount + " blobs");
        }
      }

      // Option A: Serialize to string and send in one shot (revert PR #3076)
      // This eliminates 553 write() calls for 16 blobs, trading memory for flush performance

      // Capture T2 from handler completion
      final long t2 = timingContext != null ? timingContext.getHandlerEndNs() : 0;

      // Serialize to string (jackson work happens here)
      final String jsonString = jsonObjectWriter.writeValueAsString(jsonRpcResponse);
      lazyTraceLogger(() -> jsonString);

      // Capture T2.5 (jackson serialization complete)
      final long jacksonEndNs = System.nanoTime();

      // Send response and capture T3 when flush completes
      response
          .end(jsonString)
          .onComplete(
              ar -> {
                // Capture T3 - response fully flushed
                final long t3 = System.nanoTime();

                // Log timing breakdown for engine methods
                if (timingContext != null
                    && t2 > 0
                    && timingContext.getMethod().startsWith("engine_")) {
                  final double t2t2_5Ms = (jacksonEndNs - t2) / 1_000_000.0;
                  final double t2_5t3Ms = (t3 - jacksonEndNs) / 1_000_000.0;

                  final org.slf4j.Logger LOG =
                      org.slf4j.LoggerFactory.getLogger(JsonRpcObjectExecutor.class);

                  if (LOG.isInfoEnabled() && timingContext.getHandlerStartNs() > 0) {
                    final long t0 = timingContext.getRequestParsedNs();
                    final long t1 = timingContext.getHandlerStartNs();
                    final double t0t1Ms = (t1 - t0) / 1_000_000.0;
                    final double t1t2Ms = (t2 - t1) / 1_000_000.0;

                    final String metadataStr =
                        timingContext.getMetadata() != null
                            ? " | " + timingContext.getMetadata()
                            : "";

                    LOG.info(
                        "[{}] [id={}]{} TIMING: queue={}ms exec={}ms jackson={}ms flush={}ms TOTAL={}ms | bytes={}",
                        timingContext.getMethod(),
                        timingContext.getRequestId(),
                        metadataStr,
                        String.format("%.2f", t0t1Ms),
                        String.format("%.2f", t1t2Ms),
                        String.format("%.2f", t2t2_5Ms),
                        String.format("%.2f", t2_5t3Ms),
                        String.format("%.2f", timingContext.getTotalMs(t3)),
                        jsonString.length());
                  }
                }
              });
    }
  }

  private static HttpResponseStatus status(final JsonRpcResponse response) {
    return switch (response.getType()) {
      case UNAUTHORIZED -> HttpResponseStatus.UNAUTHORIZED;
      case ERROR -> statusCodeFromError(((JsonRpcErrorResponse) response).getErrorType());
      default -> HttpResponseStatus.OK;
    };
  }

  private ObjectWriter createObjectWriter() {
    ObjectWriter writer =
        jsonRpcConfiguration.isPrettyJsonEnabled()
            ? getJsonObjectMapper().writerWithDefaultPrettyPrinter()
            : getJsonObjectMapper().writer();
    return writer
        .without(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM)
        .with(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
  }

  private static HttpResponseStatus statusCodeFromError(final RpcErrorType error) {
    return switch (error) {
      case INVALID_REQUEST, PARSE_ERROR -> HttpResponseStatus.BAD_REQUEST;
      default -> HttpResponseStatus.OK;
    };
  }
}
