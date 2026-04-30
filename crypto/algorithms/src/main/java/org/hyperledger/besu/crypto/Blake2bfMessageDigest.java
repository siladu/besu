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
package org.hyperledger.besu.crypto;

import static java.util.Arrays.copyOfRange;

import org.hyperledger.besu.nativelib.blake2bf.LibBlake2bf;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.jcajce.provider.digest.BCMessageDigest;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.Pack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The type Blake2bf message digest. */
public class Blake2bfMessageDigest extends BCMessageDigest implements Cloneable {
  private static final Logger LOG = LoggerFactory.getLogger(Blake2bfMessageDigest.class);

  /** Instantiates a new Blake2bf message digest. */
  public Blake2bfMessageDigest() {
    super(new Blake2bfDigest());
  }

  @Override
  public Blake2bfMessageDigest clone() throws CloneNotSupportedException {
    Blake2bfMessageDigest cloned = (Blake2bfMessageDigest) super.clone();
    cloned.digest = ((Blake2bfDigest) this.digest).clone();
    return cloned;
  }

  /**
   * Implementation of the `F` compression function of the Blake2b cryptographic hash function.
   *
   * <p>RFC - <a href="https://tools.ietf.org/html/rfc7693">...</a>
   *
   * <p>Adapted from - <a
   * href="https://github.com/keep-network/blake2b/blob/master/compression/f.go">...</a>
   *
   * <p>Optimized for 64-bit platforms
   */
  public static class Blake2bfDigest implements Digest, Cloneable {
    /** The constant MESSAGE_LENGTH_BYTES. */
    public static final int MESSAGE_LENGTH_BYTES = 213;

    private static final long[] IV = {
      0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL,
      0xa54ff53a5f1d36f1L, 0x510e527fade682d1L, 0x9b05688c2b3e6c1fL,
      0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
    };

    private static final byte[][] PRECOMPUTED = {
      {0, 2, 4, 6, 1, 3, 5, 7, 8, 10, 12, 14, 9, 11, 13, 15},
      {14, 4, 9, 13, 10, 8, 15, 6, 1, 0, 11, 5, 12, 2, 7, 3},
      {11, 12, 5, 15, 8, 0, 2, 13, 10, 3, 7, 9, 14, 6, 1, 4},
      {7, 3, 13, 11, 9, 1, 12, 14, 2, 5, 4, 15, 6, 10, 0, 8},
      {9, 5, 2, 10, 0, 7, 4, 15, 14, 11, 6, 3, 1, 12, 8, 13},
      {2, 6, 0, 8, 12, 10, 11, 3, 4, 7, 15, 1, 13, 5, 14, 9},
      {12, 1, 14, 4, 5, 15, 13, 10, 0, 6, 9, 8, 7, 3, 2, 11},
      {13, 7, 12, 3, 11, 14, 1, 9, 5, 15, 8, 2, 0, 4, 6, 10},
      {6, 14, 11, 0, 15, 9, 3, 8, 12, 13, 1, 10, 2, 7, 4, 5},
      {10, 8, 7, 1, 2, 4, 6, 5, 15, 9, 3, 13, 11, 14, 12, 0}
    };

    private static final int DIGEST_LENGTH = 64;

    // 4-lane 256-bit vector species for the SIMD compress path
    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_256;
    // Lane rotation shuffles for the diagonal step: rotateLeft by 1/2/3 positions
    private static final VectorShuffle<Long> ROT_L1 =
        VectorShuffle.fromArray(SPECIES, new int[] {1, 2, 3, 0}, 0);
    private static final VectorShuffle<Long> ROT_L2 =
        VectorShuffle.fromArray(SPECIES, new int[] {2, 3, 0, 1}, 0);
    private static final VectorShuffle<Long> ROT_L3 =
        VectorShuffle.fromArray(SPECIES, new int[] {3, 0, 1, 2}, 0);
    // Un-rotate: rotateRight 1/2/3 = rotateLeft 3/2/1
    private static final VectorShuffle<Long> ROT_R1 = ROT_L3;
    private static final VectorShuffle<Long> ROT_R2 = ROT_L2;
    private static final VectorShuffle<Long> ROT_R3 = ROT_L1;

    // buffer which holds serialized input for this compression function
    // [ 4 bytes for rounds ][ 64 bytes for h ][ 128 bytes for m ]
    // [ 8 bytes for t_0 ][ 8 bytes for t_1 ][ 1 byte for f ]
    private byte[] buffer;

    private int bufferPos;

    // deserialized inputs for f compression
    private long[] h;
    private long[] m;
    private long[] t;
    private boolean f;
    private long rounds; // unsigned integer represented as long

    private long[] v;
    private static boolean useNative;
    private static boolean useVector;

    static {
//      maybeEnableNative();
//      if (!useNative) {
        maybeEnableVector();
//      }
    }

    /** Instantiates a new Blake2bf digest. */
    Blake2bfDigest() {
      if (!useNative) {
        LOG.info("Native blake2bf not available");
        if (!useVector) {
          LOG.info("Vector blake2bf not available");
        }
      }

      buffer = new byte[MESSAGE_LENGTH_BYTES];
      bufferPos = 0;

      h = new long[8];
      m = new long[16];
      t = new long[2];
      f = false;
      rounds = 12;

      v = new long[16];
    }

    @Override
    public Blake2bfDigest clone() throws CloneNotSupportedException {
      Blake2bfDigest cloned = (Blake2bfDigest) super.clone();
      cloned.buffer = this.buffer.clone();
      cloned.h = this.h.clone();
      cloned.m = this.m.clone();
      cloned.t = this.t.clone();
      cloned.v = this.v.clone();
      return cloned;
    }

    /**
     * Attempt to enable the native libreary
     *
     * @return true if the native library was successfully enabled.
     */
    public static boolean maybeEnableNative() {
      try {
        useNative = LibBlake2bf.ENABLED;
      } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
        LOG.info("blake2bf native precompile not available: {}", e.getMessage());
        useNative = false;
      }
      return useNative;
    }

    /** Disable native. */
    public static void disableNative() {
      useNative = false;
    }

    /**
     * Is native.
     *
     * @return the boolean
     */
    public static boolean isNative() {
      return useNative;
    }

    /**
     * Attempt to enable the Java Vector API compress path.
     *
     * @return true if the vector path was enabled.
     */
    public static boolean maybeEnableVector() {
      try {
        useVector = SPECIES.length() == 4;
      } catch (Throwable e) {
        LOG.info("Vector blake2bf not available: {}", e.getMessage());
        useVector = false;
      }
      return useVector;
    }

    /** Disable the vector compress path (forces scalar fallback). */
    public static void disableVector() {
      useVector = false;
    }

    /**
     * Is vector.
     *
     * @return true if the vector path is active.
     */
    public static boolean isVector() {
      return useVector;
    }

    @Override
    public String getAlgorithmName() {
      return "BLAKE2f";
    }

    @Override
    public int getDigestSize() {
      return DIGEST_LENGTH;
    }

    /**
     * update the message digest with a single byte.
     *
     * @param in the input byte to be entered.
     */
    @Override
    public void update(final byte in) {
      checkSize(1);
      buffer[bufferPos] = in;
      bufferPos++;
      maybeInitialize();
    }

    /**
     * update the message digest with a block of bytes.
     *
     * @param in the byte array containing the data.
     * @param offset the offset into the byte array where the data starts.
     * @param len the length of the data.
     */
    @Override
    public void update(final byte[] in, final int offset, final int len) {
      if (in == null || len == 0) {
        return;
      }

      checkSize(len);

      System.arraycopy(in, offset, buffer, bufferPos, len);
      bufferPos += len;

      maybeInitialize();
    }

    private void checkSize(final int len) {
      if (len > MESSAGE_LENGTH_BYTES - bufferPos) {
        throw new IllegalArgumentException(
            "Attempting to update buffer with "
                + len
                + " byte(s) but there is "
                + (MESSAGE_LENGTH_BYTES - bufferPos)
                + " byte(s) left to fill");
      }
    }

    private void maybeInitialize() {
      if (!useNative && bufferPos == MESSAGE_LENGTH_BYTES) {
        initialize();
      }
    }

    /**
     * close the digest, producing the final digest value. The doFinal call leaves the digest reset.
     *
     * @param out the array the digest is to be copied into.
     * @param offset the offset into the out array the digest is to start at.
     */
    @Override
    public int doFinal(final byte[] out, final int offset) {
      if (bufferPos != 213) {
        throw new IllegalStateException("The buffer must be filled with 213 bytes");
      }

      if (useNative) {
        LibBlake2bf.blake2bf_eip152(out, buffer);
      } else {
        if (useVector) {
          compressVector();
        } else {
          compress();
        }
        for (int i = 0; i < h.length; i++) {
          System.arraycopy(Pack.longToLittleEndian(h[i]), 0, out, i * 8, 8);
        }
      }

      reset();

      return 0;
    }

    /** Reset the digest back to it's initial state. */
    @Override
    public void reset() {
      bufferPos = 0;
      Arrays.fill(buffer, (byte) 0);
      if (!useNative) {
        Arrays.fill(h, 0);
        Arrays.fill(m, (byte) 0);
        Arrays.fill(t, 0);
        f = false;
        rounds = 12;
        Arrays.fill(v, 0);
      }
    }

    private void initialize() {
      rounds = Integer.toUnsignedLong(bytesToInt(copyOfRange(buffer, 0, 4)));

      for (int i = 0; i < h.length; i++) {
        final int offset = 4 + i * 8;
        h[i] = bytesToLong((copyOfRange(buffer, offset, offset + 8)));
      }

      for (int i = 0; i < 16; i++) {
        final int offset = 68 + i * 8;
        m[i] = bytesToLong(copyOfRange(buffer, offset, offset + 8));
      }

      t[0] = bytesToLong(copyOfRange(buffer, 196, 204));
      t[1] = bytesToLong(copyOfRange(buffer, 204, 212));

      f = buffer[212] != 0;
    }

    private int bytesToInt(final byte[] bytes) {
      return Pack.bigEndianToInt(bytes, 0);
    }

    private long bytesToLong(final byte[] bytes) {
      return Pack.littleEndianToLong(bytes, 0);
    }

    /**
     * F is a compression function for BLAKE2b. It takes as an argument the state vector `h`,
     * message block vector `m`, offset counter `t`, final block indicator flag `f`, and number of
     * rounds `rounds`. The state vector provided as the first parameter is modified by the
     * function.
     */
    private void compress() {

      long t0 = t[0];
      long t1 = t[1];

      System.arraycopy(h, 0, v, 0, 8);
      System.arraycopy(IV, 0, v, 8, 8);

      v[12] ^= t0;
      v[13] ^= t1;

      if (f) {
        v[14] ^= 0xffffffffffffffffL;
      }

      for (long j = 0; j < rounds; ++j) {
        byte[] s = PRECOMPUTED[(int) (j % 10)];

        v[0] += m[s[0]] + v[4];
        v[12] = Long.rotateLeft(v[12] ^ v[0], -32);
        v[8] += v[12];
        v[4] = Long.rotateLeft(v[4] ^ v[8], -24);

        v[0] += m[s[4]] + v[4];
        v[12] = Long.rotateLeft(v[12] ^ v[0], -16);
        v[8] += v[12];
        v[4] = Long.rotateLeft(v[4] ^ v[8], -63);
        v[1] += m[s[1]] + v[5];
        v[13] = Long.rotateLeft(v[13] ^ v[1], -32);
        v[9] += v[13];
        v[5] = Long.rotateLeft(v[5] ^ v[9], -24);

        v[1] += m[s[5]] + v[5];
        v[13] = Long.rotateLeft(v[13] ^ v[1], -16);
        v[9] += v[13];
        v[5] = Long.rotateLeft(v[5] ^ v[9], -63);
        v[2] += m[s[2]] + v[6];
        v[14] = Long.rotateLeft(v[14] ^ v[2], -32);
        v[10] += v[14];
        v[6] = Long.rotateLeft(v[6] ^ v[10], -24);

        v[2] += m[s[6]] + v[6];
        v[14] = Long.rotateLeft(v[14] ^ v[2], -16);
        v[10] += v[14];
        v[6] = Long.rotateLeft(v[6] ^ v[10], -63);
        v[3] += m[s[3]] + v[7];
        v[15] = Long.rotateLeft(v[15] ^ v[3], -32);
        v[11] += v[15];
        v[7] = Long.rotateLeft(v[7] ^ v[11], -24);

        v[3] += m[s[7]] + v[7];
        v[15] = Long.rotateLeft(v[15] ^ v[3], -16);
        v[11] += v[15];
        v[7] = Long.rotateLeft(v[7] ^ v[11], -63);
        v[0] += m[s[8]] + v[5];
        v[15] = Long.rotateLeft(v[15] ^ v[0], -32);
        v[10] += v[15];
        v[5] = Long.rotateLeft(v[5] ^ v[10], -24);

        v[0] += m[s[12]] + v[5];
        v[15] = Long.rotateLeft(v[15] ^ v[0], -16);
        v[10] += v[15];
        v[5] = Long.rotateLeft(v[5] ^ v[10], -63);
        v[1] += m[s[9]] + v[6];
        v[12] = Long.rotateLeft(v[12] ^ v[1], -32);
        v[11] += v[12];
        v[6] = Long.rotateLeft(v[6] ^ v[11], -24);

        v[1] += m[s[13]] + v[6];
        v[12] = Long.rotateLeft(v[12] ^ v[1], -16);
        v[11] += v[12];
        v[6] = Long.rotateLeft(v[6] ^ v[11], -63);
        v[2] += m[s[10]] + v[7];
        v[13] = Long.rotateLeft(v[13] ^ v[2], -32);
        v[8] += v[13];
        v[7] = Long.rotateLeft(v[7] ^ v[8], -24);

        v[2] += m[s[14]] + v[7];
        v[13] = Long.rotateLeft(v[13] ^ v[2], -16);
        v[8] += v[13];
        v[7] = Long.rotateLeft(v[7] ^ v[8], -63);
        v[3] += m[s[11]] + v[4];
        v[14] = Long.rotateLeft(v[14] ^ v[3], -32);
        v[9] += v[14];
        v[4] = Long.rotateLeft(v[4] ^ v[9], -24);

        v[3] += m[s[15]] + v[4];
        v[14] = Long.rotateLeft(v[14] ^ v[3], -16);
        v[9] += v[14];
        v[4] = Long.rotateLeft(v[4] ^ v[9], -63);
      }

      // update h:
      for (int offset = 0; offset < h.length; offset++) {
        h[offset] ^= v[offset] ^ v[offset + 8];
      }
    }

    /**
     * SIMD compress using Java Vector API. Packs v[0..15] into four 4-lane 256-bit LongVectors:
     *
     * <pre>
     *   va = {v[0],  v[1],  v[2],  v[3] }   // column a-words
     *   vb = {v[4],  v[5],  v[6],  v[7] }   // column b-words
     *   vc = {v[8],  v[9],  v[10], v[11]}   // column c-words
     *   vd = {v[12], v[13], v[14], v[15]}   // column d-words
     * </pre>
     *
     * Each round applies G() first on the 4 columns (straight vector ops), then on the 4 diagonals
     * (lane-rotate b/c/d to align diagonals as columns, apply G(), un-rotate).
     */
    private void compressVector() {
      long[] vl = new long[16];
      System.arraycopy(h, 0, vl, 0, 8);
      System.arraycopy(IV, 0, vl, 8, 8);
      vl[12] ^= t[0];
      vl[13] ^= t[1];
      if (f) {
        vl[14] ^= 0xffffffffffffffffL;
      }

      LongVector va = LongVector.fromArray(SPECIES, vl, 0);
      LongVector vb = LongVector.fromArray(SPECIES, vl, 4);
      LongVector vc = LongVector.fromArray(SPECIES, vl, 8);
      LongVector vd = LongVector.fromArray(SPECIES, vl, 12);

      long[] msg = new long[4];

      for (long j = 0; j < rounds; j++) {
        byte[] s = PRECOMPUTED[(int) (j % 10)];

        // Column step: G() on all 4 columns simultaneously.
        // mColLo = {m[s[0]], m[s[1]], m[s[2]], m[s[3]]}  (x argument per column)
        // mColHi = {m[s[4]], m[s[5]], m[s[6]], m[s[7]]}  (y argument per column)
        msg[0] = m[s[0]];
        msg[1] = m[s[1]];
        msg[2] = m[s[2]];
        msg[3] = m[s[3]];
        LongVector mColLo = LongVector.fromArray(SPECIES, msg, 0);
        msg[0] = m[s[4]];
        msg[1] = m[s[5]];
        msg[2] = m[s[6]];
        msg[3] = m[s[7]];
        LongVector mColHi = LongVector.fromArray(SPECIES, msg, 0);

        // G(va, vb, vc, vd) — all 4 columns at once.
        // ROR(x,32)=ROL(x,32), ROR(x,24)=ROL(x,40), ROR(x,16)=ROL(x,48), ROR(x,63)=ROL(x,1)
        va = va.add(vb).add(mColLo);
        vd = vd.lanewise(VectorOperators.XOR, va).lanewise(VectorOperators.ROL, 32L);
        vc = vc.add(vd);
        vb = vb.lanewise(VectorOperators.XOR, vc).lanewise(VectorOperators.ROL, 40L);
        va = va.add(vb).add(mColHi);
        vd = vd.lanewise(VectorOperators.XOR, va).lanewise(VectorOperators.ROL, 48L);
        vc = vc.add(vd);
        vb = vb.lanewise(VectorOperators.XOR, vc).lanewise(VectorOperators.ROL, 1L);

        // Diagonal step: G() on the 4 diagonals (0,5,10,15), (1,6,11,12), (2,7,8,13), (3,4,9,14).
        // mDiagLo = {m[s[8]],  m[s[9]],  m[s[10]], m[s[11]]}  (x per diagonal)
        // mDiagHi = {m[s[12]], m[s[13]], m[s[14]], m[s[15]]}  (y per diagonal)
        msg[0] = m[s[8]];
        msg[1] = m[s[9]];
        msg[2] = m[s[10]];
        msg[3] = m[s[11]];
        LongVector mDiagLo = LongVector.fromArray(SPECIES, msg, 0);
        msg[0] = m[s[12]];
        msg[1] = m[s[13]];
        msg[2] = m[s[14]];
        msg[3] = m[s[15]];
        LongVector mDiagHi = LongVector.fromArray(SPECIES, msg, 0);

        // TODO(human): Implement the diagonal G() step.
        //
        // BLAKE2b diagonals: (0,5,10,15), (1,6,11,12), (2,7,8,13), (3,4,9,14).
        // Rotate vb/vc/vd so each diagonal aligns as a column, apply G(), then un-rotate.
        //
        // Step 1 - rotate to align diagonals:
        //   vbDiag = vb.rearrange(ROT_L1)  // {v[5], v[6], v[7], v[4]}
        LongVector vbDiag = vb.rearrange(ROT_L1);
        //   vcDiag = vc.rearrange(ROT_L2)  // {v[10], v[11], v[8], v[9]}
        LongVector vcDiag = vc.rearrange(ROT_L2);
        //   vdDiag = vd.rearrange(ROT_L3)  // {v[15], v[12], v[13], v[14]}
        LongVector vdDiag = vd.rearrange(ROT_L3);
        //
        // Step 2 - apply G(va, vbDiag, vcDiag, vdDiag) with mDiagLo/mDiagHi
        //   (same ROL/XOR/add pattern as the column step above)
        /*
       FUNCTION G( v[0..15], a, b, c, d, x, y )
       |
       |   v[a] := (v[a] + v[b] + x) mod 2**w
       |   v[d] := (v[d] ^ v[a]) >>> R1
       |   v[c] := (v[c] + v[d])     mod 2**w
       |   v[b] := (v[b] ^ v[c]) >>> R2
       |   v[a] := (v[a] + v[b] + y) mod 2**w
       |   v[d] := (v[d] ^ v[a]) >>> R3
       |   v[c] := (v[c] + v[d])     mod 2**w
       |   v[b] := (v[b] ^ v[c]) >>> R4
       |
       |   RETURN v[0..15]
       |
       END FUNCTION.
         */

        // G(va, vb, vc, vd) — all 4 columns at once.
        // ROR(x,32)=ROL(x,32), ROR(x,24)=ROL(x,40), ROR(x,16)=ROL(x,48), ROR(x,63)=ROL(x,1)
        va = va.add(vbDiag).add(mDiagLo);
        vdDiag = vdDiag.lanewise(VectorOperators.XOR, va).lanewise(VectorOperators.ROL, 32L);
        vcDiag = vcDiag.add(vdDiag);
        vbDiag = vbDiag.lanewise(VectorOperators.XOR, vcDiag).lanewise(VectorOperators.ROL, 40L);
        va = va.add(vbDiag).add(mDiagHi);
        vdDiag = vdDiag.lanewise(VectorOperators.XOR, va).lanewise(VectorOperators.ROL, 48L);
        vcDiag = vcDiag.add(vdDiag);
        vbDiag = vbDiag.lanewise(VectorOperators.XOR, vcDiag).lanewise(VectorOperators.ROL, 1L);
        //
        // Step 3 - un-rotate back:
        vb = vbDiag.rearrange(ROT_R1);
        vc = vcDiag.rearrange(ROT_R2);
        vd = vdDiag.rearrange(ROT_R3);
        //
        // Expected: ~10 lines of vector ops + 3 un-rotate assignments.
      }

      va.intoArray(vl, 0);
      vb.intoArray(vl, 4);
      vc.intoArray(vl, 8);
      vd.intoArray(vl, 12);

      for (int offset = 0; offset < h.length; offset++) {
        h[offset] ^= vl[offset] ^ vl[offset + 8];
      }
    }
  }
}
