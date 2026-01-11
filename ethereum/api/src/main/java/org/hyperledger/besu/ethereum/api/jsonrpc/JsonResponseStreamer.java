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
package org.hyperledger.besu.ethereum.api.jsonrpc;

import org.hyperledger.besu.ethereum.api.jsonrpc.context.RpcTimingContext;
import org.hyperledger.besu.plugin.services.metrics.Histogram;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonResponseStreamer extends OutputStream {

  private static final Logger LOG = LoggerFactory.getLogger(JsonResponseStreamer.class);

  private final HttpServerResponse response;
  private final SocketAddress remoteAddress;
  private final byte[] singleByteBuf = new byte[1];
  private boolean chunked = false;
  private boolean closed = false;
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final RpcTimingContext timingContext;
  private final LabelledMetric<Histogram> handlerToFlushHistogram;
  private long jacksonEndNs = 0;  // Track when Jackson completes

  public JsonResponseStreamer(
      final HttpServerResponse response,
      final SocketAddress socketAddress,
      final RpcTimingContext timingContext,
      final LabelledMetric<Histogram> handlerToFlushHistogram) {
    this.response = response;
    this.remoteAddress = socketAddress;
    this.timingContext = timingContext;
    this.handlerToFlushHistogram = handlerToFlushHistogram;
    this.response.exceptionHandler(
        event -> {
          LOG.debug("Write to remote address {} failed", remoteAddress, event);
          failure.set(event);
        });
  }

  @Override
  public void write(final int b) throws IOException {
    singleByteBuf[0] = (byte) b;
    write(singleByteBuf, 0, 1);
  }

  @Override
  public void write(final byte[] bbuf, final int off, final int len) throws IOException {
    stopOnFailureOrClosed();

    if (!chunked) {
      response.setChunked(true);
      chunked = true;
    }

    Buffer buf = Buffer.buffer(len);
    buf.appendBytes(bbuf, off, len);
    response.write(buf).onFailure(this::handleFailure);
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      // CAPTURE T2.5 - Jackson serialization just completed
      jacksonEndNs = System.nanoTime();

      // Get T2 from handler completion
      final long t2 = timingContext != null ? timingContext.getHandlerEndNs() : 0;

      // Call response.end() and hook into completion to capture T3
      response
          .end()
          .onComplete(
              ar -> {
                // CAPTURE T3 - Response fully flushed to socket
                final long t3 = System.nanoTime();

                if (timingContext != null && t2 > 0 && jacksonEndNs > 0) {
                  final double t2t2_5Ms = (jacksonEndNs - t2) / 1_000_000.0;
                  final double t2_5t3Ms = (t3 - jacksonEndNs) / 1_000_000.0;
                  final double t2t3Ms = (t3 - t2) / 1_000_000.0;

                  // Only record for engine methods
                  if (timingContext.getMethod().startsWith("engine_")) {
                    // Record metric
                    if (handlerToFlushHistogram != null) {
                      handlerToFlushHistogram
                          .labels(timingContext.getMethod())
                          .observe(t2t3Ms / 1000.0); // Convert to seconds
                    }

                    // Log at DEBUG level with breakdown
                    if (LOG.isDebugEnabled()) {
                      LOG.debug(
                          "[{}] [id={}] Response flushed T3, jackson={}ms, flush={}ms, serialization={}ms, total={}ms",
                          timingContext.getMethod(),
                          timingContext.getRequestId(),
                          String.format("%.2f", t2t2_5Ms),
                          String.format("%.2f", t2_5t3Ms),
                          String.format("%.2f", t2t3Ms),
                          String.format("%.2f", timingContext.getTotalMs(t3)));
                    }

                    // CONSOLIDATED TIMING LOG - All phases in one line
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
                          "[{}] [id={}]{} TIMING: queue={}ms exec={}ms jackson={}ms flush={}ms TOTAL={}ms",
                          timingContext.getMethod(),
                          timingContext.getRequestId(),
                          metadataStr,
                          String.format("%.2f", t0t1Ms),
                          String.format("%.2f", t1t2Ms),
                          String.format("%.2f", t2t2_5Ms),
                          String.format("%.2f", t2_5t3Ms),
                          String.format("%.2f", timingContext.getTotalMs(t3)));
                    }
                  }
                }
              });

      closed = true;
    }
  }

  private void stopOnFailureOrClosed() throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }

    Throwable t = failure.get();
    if (t != null) {
      LOG.debug("Stop writing to remote address {} due to a failure", remoteAddress, t);
      throw (t instanceof IOException) ? (IOException) t : new IOException(t);
    }
  }

  private void handleFailure(final Throwable t) {
    LOG.debug("Write to remote address {} failed", remoteAddress, t);
    failure.set(t);
  }
}
