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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine;

import static ethereum.ckzg4844.CKZG4844JNI.BLS_MODULUS;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.OSAKA;
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.computeBlobKzgProofs;
import static org.hyperledger.besu.ethereum.mainnet.MainnetBlobsValidator.hashCommitment;

import org.hyperledger.besu.datatypes.BlobType;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlobAndProofV2;
import org.hyperledger.besu.ethereum.core.kzg.Blob;
import org.hyperledger.besu.ethereum.core.kzg.BlobProofBundle;
import org.hyperledger.besu.ethereum.core.kzg.KZGCommitment;
import org.hyperledger.besu.ethereum.core.kzg.KZGProof;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.util.HexUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;

import ethereum.ckzg4844.CKZG4844JNI;
import io.vertx.core.Vertx;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes48;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EngineGetBlobsV2 extends ExecutionEngineJsonRpcMethod {
  private static final Logger LOG = LoggerFactory.getLogger(EngineGetBlobsV2.class);
  public static final int REQUEST_MAX_VERSIONED_HASHES = 72;
  private static final int BUNDLE_COUNT = 100;

  private final List<BlobProofBundle> blobBundles;

  private final Counter requestedCounter;
  private final Counter availableCounter;
  private final Counter hitCounter;
  private final Counter missCounter;
  private final Optional<Long> osakaMilestone;

  public EngineGetBlobsV2(
      final Vertx vertx,
      final ProtocolContext protocolContext,
      final ProtocolSchedule protocolSchedule,
      final EngineCallListener engineCallListener,
      final TransactionPool transactionPool,
      final MetricsSystem metricsSystem) {

    super(vertx, protocolSchedule, protocolContext, engineCallListener);

    this.blobBundles = createBlobBundles();

    this.requestedCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.RPC,
            "execution_engine_getblobs_requested_total",
            "Number of blobs requested via engine_getBlobsV2");

    this.availableCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.RPC,
            "execution_engine_getblobs_available_total",
            "Number of blobs returned via engine_getBlobsV2");

    this.hitCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.RPC,
            "execution_engine_getblobs_hit_total",
            "Number of engine_getBlobsV2 calls returning blobs");

    this.missCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.RPC,
            "execution_engine_getblobs_miss_total",
            "Number of engine_getBlobsV2 calls returning null");

    this.osakaMilestone = protocolSchedule.milestoneFor(OSAKA);
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_GET_BLOBS_V2.getMethodName();
  }

  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext requestContext) {
    final VersionedHash[] versionedHashes = extractVersionedHashes(requestContext);

    if (versionedHashes.length > REQUEST_MAX_VERSIONED_HASHES) {
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(),
          RpcErrorType.INVALID_ENGINE_GET_BLOBS_TOO_LARGE_REQUEST);
    }

    requestedCounter.inc(versionedHashes.length);

    if (versionedHashes.length == 0 || versionedHashes.length > blobBundles.size()) {
      missCounter.inc();
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
    }

    final List<BlobProofBundle> selectedBundles =
        IntStream.range(0, versionedHashes.length)
            .mapToObj(i -> blobBundles.get((int) (Math.random() * blobBundles.size())))
            .toList();
    availableCounter.inc(selectedBundles.size());

    LOG.debug(
        "Returning {} blob bundles for {} requested hashes",
        selectedBundles.size(),
        versionedHashes.length);

    final List<BlobAndProofV2> results =
        selectedBundles.parallelStream().map(this::createBlobAndProofV2).toList();

    hitCounter.inc();
    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), results);
  }

  private VersionedHash[] extractVersionedHashes(final JsonRpcRequestContext requestContext) {
    try {
      return requestContext.getRequiredParameter(0, VersionedHash[].class);
    } catch (JsonRpcParameter.JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid versioned hashes parameter (index 0)",
          RpcErrorType.INVALID_VERSIONED_HASHES_PARAMS,
          e);
    }
  }

  private List<BlobProofBundle> createBlobBundles() {
    final List<BlobProofBundle> bundles = new ArrayList<>(BUNDLE_COUNT);

    for (int i = 0; i < BUNDLE_COUNT; i++) {
      bundles.add(createBundle(i));
      LOG.info("Created blob proof bundle {}", i);
    }

    return List.copyOf(bundles);
  }

  private static final int BYTES_PER_FIELD_ELEMENT = 32;
  private static final int FIELD_ELEMENTS_PER_BLOB = 4096;
  private static final int BLOB_BYTES = BYTES_PER_FIELD_ELEMENT * FIELD_ELEMENTS_PER_BLOB; // 131072

  private BlobProofBundle createBundle(final int seed) {
    final Random random = new Random(seed);
    final byte[] rawMaterial = new byte[BLOB_BYTES];
    final byte[] fe = new byte[BYTES_PER_FIELD_ELEMENT];

    for (int i = 0; i < FIELD_ELEMENTS_PER_BLOB; i++) {
      BigInteger v;

      // rejection sampling: v < BLS_MODULUS
      do {
        random.nextBytes(fe);
        v = new BigInteger(1, fe); // big-endian, unsigned
      } while (v.compareTo(BLS_MODULUS) >= 0);

      System.arraycopy(fe, 0, rawMaterial, i * BYTES_PER_FIELD_ELEMENT,
              BYTES_PER_FIELD_ELEMENT);
    }

    final Bytes48 commitment = Bytes48.wrap(CKZG4844JNI.blobToKzgCommitment(rawMaterial));

    final Blob blob = new Blob(Bytes.wrap(rawMaterial));
    final KZGCommitment kzgCommitment = new KZGCommitment(commitment);

    final List<KZGProof> proofs = computeBlobKzgProofs(blob);

    return new BlobProofBundle(
        BlobType.KZG_CELL_PROOFS, blob, kzgCommitment, proofs, hashCommitment(kzgCommitment));
  }

  private BlobAndProofV2 createBlobAndProofV2(final BlobProofBundle blobProofBundle) {
    return new BlobAndProofV2(
        HexUtils.toFastHex(blobProofBundle.getBlob().getData(), true),
        blobProofBundle.getKzgProof().parallelStream()
            .map(proof -> HexUtils.toFastHex(proof.getData(), true))
            .toList());
  }

  @Override
  protected ValidationResult<RpcErrorType> validateForkSupported(final long currentTimestamp) {
    return ForkSupportHelper.validateForkSupported(OSAKA, osakaMilestone, currentTimestamp);
  }
}
