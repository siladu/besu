# Besu GraalVM Native Image — working build

Both the full Besu client and evmtool now compile to GraalVM native images and
run real workloads. Built and tested with GraalVM CE 25.0.2 (matches Besu's
Java 25 toolchain) on macOS aarch64.

## Results

| Measurement (M4 Max) | Native | JVM (dist) | Speedup |
|---|---|---|---|
| `evmtool --version` | 13.6 ms | 529 ms | **39×** |
| `besu --version` | 55 ms | 889 ms | **16×** |
| dev node, time-to-first-RPC-response | 2.26 s | 3.10 s | 1.4× |
| binary size | besu 175 MB, evmtool 111 MB | — | — |

Verified working natively:
- `besu --version`, `--help`, full picocli CLI model
- `besu --network=dev` node boot: RocksDB (JNI), log4j2 (custom Besu plugins
  included), Vert.x/Netty JSON-RPC HTTP answering `eth_*` calls, clean
  SIGTERM shutdown — zero ERROR lines in the log
- mainnet startup path incl. native-crypto requirement checks: secp256k1,
  secp256r1, blake2bf, gnark EIP-196/2537 (all via JNA), boringssl, and
  c-kzg-4844 (JNI) with the bundled trusted setup
- `evmtool benchmark` (sha256 / EcRecover / kzgPointEval) with native libs
  engaged inside the image

## How to build

```bash
sdk install java 25.0.2-graalce        # once
GRAALVM_HOME=~/.sdkman/candidates/java/25.0.2-graalce ./gradlew :nativeCompile :ethereum:evmtool:nativeCompile
# -> build/native/nativeCompile/besu
# -> ethereum/evmtool/build/native/nativeCompile/evmtool
```

Native-image analysis takes ~70 s per binary on an M4 Max (16 threads).

## What changed

- `build.gradle` (root): `org.graalvm.buildtools.native` 0.10.6 added
  (`apply false` in `plugins{}`, applied to the root project which owns the
  `application` plugin / `org.hyperledger.besu.Besu` main class). A
  `graalvmNative` block sets `sharedLibrary = false` (the `java-library`
  plugin otherwise flips the image to a shared library — this is why the
  pre-existing evmtool native build produced a `.dylib`), `--no-fallback`,
  `-H:+AddAllCharsets`, `--enable-url-protocols=http,https`, and wires the
  metadata directories.
- `ethereum/evmtool/build.gradle`: plugin version now inherited from root;
  `graalvmNative` block added (executable, config dirs). The orphaned
  `src/main/graal/reflection-config.json` from PR #5192 was renamed to the
  convention name `reflect-config.json` and moved to `src/main/graal-manual/`
  (it was never wired in, and its Caffeine entries had drifted: registers
  `SSMSW`, code now needs `SSMS`).
- `plugins/rocksdb/build.gradle`: `picocli-codegen` annotation processor so
  `RocksDBCLIOptions` gets reflect-config generated at compile time.
- `gradle/verification-metadata.xml`: sha256 entry for
  `graalvm-reachability-metadata-0.10.6-repository.zip` (the plugin's
  metadata-repository download; only the plugin jars were previously listed).
- `app/src/main/graal/reachability-metadata.json`: agent-captured metadata
  (see below). `app/src/main/graal-manual/`: hand-curated entries the agent
  cannot see — `META-INF/besu-artifacts-catalog.json` (PluginVerifier reads it
  through the plugin URLClassLoader), PluginVerifier's Jackson record DTOs,
  genesis/profile resources for all named networks, KZG trusted setups,
  GraphQL schema.
- `ethereum/evmtool/src/main/graal/reachability-metadata.json`: agent capture
  from benchmark runs.

## Regenerating the agent metadata

The bulk of the reflection/JNI/resource metadata is recorded by running the
JVM dist under the tracing agent (GraalVM's `java` required):

```bash
./gradlew installDist
export JAVA_HOME=~/.sdkman/candidates/java/25.0.2-graalce
A="-agentlib:native-image-agent=config-merge-dir=app/src/main/graal"
JAVA_OPTS=$A build/install/besu/bin/besu --version
JAVA_OPTS=$A build/install/besu/bin/besu --help > /dev/null
JAVA_OPTS=$A build/install/besu/bin/besu --network=dev  --data-path=/tmp/d1 --rpc-http-enabled &   # + RPC traffic, then SIGTERM
JAVA_OPTS=$A build/install/besu/bin/besu --network=mainnet --data-path=/tmp/d2 --rpc-http-enabled --p2p-enabled=false &  # loads KZG+BLS, then SIGTERM
```

(Use `config-output-dir` for the first run to start fresh. Same pattern for
evmtool into `ethereum/evmtool/src/main/graal`, exercising
`benchmark sha256 EcRecover kzgPointEval` and `block-test` over an EEST
fixture — the fixture file is deserialized in full even with a non-matching
`--test-name`, so one executed test plus parse-only runs of other fixture
families captures the whole `BlockchainReferenceTestCaseSpec` Jackson
surface cheaply.)

**Important: boot each network twice over the same data dir.** A restart
exercises the Jackson *read* paths for `version-metadata.json`
(`VersionMetadata`) and the database metadata — a fresh boot only records the
write paths, and the native binary then fails with
`MissingReflectionRegistrationError` on any pre-existing data directory.

More coverage = more agent runs: any code path not exercised under the agent
and not statically analyzable may throw `MissingReflectionRegistrationError`
etc. at runtime in the native binary. Exercised so far: dev + mainnet boot
(fresh and restart), the RPCs listed above, and the benchmark suite.

## Known limitations / caveats

1. **External plugins cannot work** — `BesuPluginContextImpl` loads plugin
   jars via `URLClassLoader`; native images cannot load classes at runtime.
   Built-in plugins (RocksDB, in-memory storage, health checks) are directly
   instantiated and work. A native Besu should probably force
   `--plugins-external-enabled=false` eventually.
2. **JSON-RPC long tail** — Jackson DTOs for RPC methods not exercised under
   the agent may need metadata additions. The common eth/net/web3/admin/txpool
   read paths are covered; anything missing shows up as a
   `MissingReflectionRegistrationError` and is a one-line metadata fix.
3. **Version metadata cosmetic warning** — `BesuVersionUtils` reads the jar
   manifest via the class URL; there is no jar in a native image, so startup
   logs `Partial or missing Besu version metadata` (version still prints;
   commit hash missing). Fixable later with a build-time property.
4. **picocli-codegen crashes on `:app`** — adding the annotation processor to
   the app module fails compilation with an NPE inside the processor
   (`TypeElement.getSuperclass()` on a null element, picocli 4.7.7, likely an
   interaction with Dagger-generated types). Not needed — the agent capture
   of `--help` covers the full CLI model — but worth an upstream picocli
   issue.
5. **Serial GC / no JIT** — native-image on macOS only has serial GC; G1 is
   available on Linux (`--gc=G1`). Peak throughput does not match JVM C2:
   on EEST 100M-gas benchmark blocks (`block-test`), native imports run
   ~1.3–2× slower per block than warmed-up JVM (e.g. ADD 845 ms vs 451 ms),
   with identical block hashes. Native image is a startup-latency play
   (13 ms vs 530 ms evmtool startup): it wins for many short invocations,
   t8n server mode, and CLI usage — not for giant single blocks. To close
   the gap: `-O3`/`-march=native` buildArgs, or Oracle GraalVM with PGO
   (profile a `block-test` run, rebuild with the profile).
6. The dev/mainnet data dirs used during agent capture are throwaway
   (`tmp/`); the recorded metadata does not reference them.

## Suggested next steps

- Linux build (+ `--gc=G1`, and a musl/static variant for containers) and a
  CI job publishing native artifacts.
- Exercise acceptance-test traffic under the agent to widen RPC coverage.
- `t8n`/`state-test` evmtool runs under the agent (execution-spec-tests
  server mode benefits hugely from 13 ms startup).
- Upstream issues: picocli-codegen NPE; consider a Besu code guard that
  disables external-plugin loading when `ImageInfo.inImageCode()`.
