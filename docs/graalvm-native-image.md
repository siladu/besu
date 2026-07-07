# GraalVM Native Image: building, troubleshooting, re-recording metadata

Both the full Besu client and evmtool build as GraalVM native executables:

```bash
sdk install java 25.0.2-graalce   # once; must match Besu's Java toolchain major
GRAALVM_HOME=~/.sdkman/candidates/java/25.0.2-graalce \
  ./gradlew :nativeCompile :ethereum:evmtool:nativeCompile
# -> build/native/nativeCompile/besu
# -> ethereum/evmtool/build/native/nativeCompile/evmtool
```

Native-image only includes reflection/JNI/resource/serialization targets that
are registered in *reachability metadata*. Most of Besu's metadata was
recorded by running the JVM distribution under the tracing agent. Any code
path that was never exercised during recording (and is not statically
analyzable) can fail **at runtime** in the native binary. This document is
the runbook for diagnosing and fixing those failures.

## Where the metadata lives

| Binary | Agent-recorded (regenerated wholesale) | Hand-curated (never overwritten) |
|---|---|---|
| `besu` | `app/src/main/graal/` | `app/src/main/graal-manual/` |
| `evmtool` | `ethereum/evmtool/src/main/graal/` | `ethereum/evmtool/src/main/graal-manual/` |

Wired via `graalvmNative { binaries.main.configurationFileDirectories }` in
the root `build.gradle` (besu) and `ethereum/evmtool/build.gradle` (evmtool).
Fix the directory belonging to the binary that failed.

## How runtime failures present

- `org.graalvm.nativeimage.MissingReflectionRegistrationError` — names the
  exact missing member and **prints the JSON snippet to add**.
- Jackson `InvalidDefinitionException: Cannot construct instance of X (no
  Creators ...)` — same root cause (unregistered constructor); newer Jackson
  even appends "this appears to be a native image".
- `MissingResourceRegistrationError`, or `getResource(...)` returning null
  where the JVM finds the file — missing resource glob.
- `NoSuchMethodException`/`NoSuchMethodError` via MethodHandles (e.g.
  Caffeine selecting a cache variant like `SSMS` at runtime) — unregistered
  constructor on a dynamically chosen class.
- `UnsatisfiedLinkError` — missing JNI registration or the native `.so`/
  `.dylib` resource wasn't included in the image.

## Decision: quick manual fix vs re-record

**Quick manual fix** — when the error names a single member (typical for
`MissingReflectionRegistrationError`):

1. Copy the JSON snippet from the error message into the `reflection` array
   of the binary's `graal-manual/reachability-metadata.json` (create the
   array if absent). For Jackson-deserialized types, prefer registering the
   whole type rather than one constructor:
   ```json
   {
     "type": "com.example.SomeDto",
     "allDeclaredConstructors": true,
     "allDeclaredMethods": true,
     "allDeclaredFields": true
   }
   ```
   For a missing resource, add `{"glob": "path/inside/classpath.ext"}` to the
   `resources` array.
2. Rebuild (`:nativeCompile` or `:ethereum:evmtool:nativeCompile`) and re-run
   the failing command.

**Re-record under the agent** — when you enabled a whole new scenario (a new
subcommand, a new RPC family, a new fixture format, a feature area that was
never captured). One error usually means the *first* of many missing entries
on that path; the agent captures the entire family in one pass.

## Re-recording procedure

1. **Rebuild the JVM dist first** so it matches current sources:
   `./gradlew installDist`
2. The agent only exists in GraalVM's JVM:
   `export JAVA_HOME=~/.sdkman/candidates/java/25.0.2-graalce`
3. Point the agent at the binary's *agent* directory. Use
   `config-merge-dir` to add scenarios to the existing metadata (the normal
   case); use `config-output-dir` into a scratch dir only for a wholesale
   re-record, then replace the checked-in file and re-run the full suite
   below.
4. Run the scenario that failed **plus** the standard suite (below) so
   nothing regresses. The agent writes on clean JVM exit — SIGTERM is fine,
   SIGKILL loses the recording.
5. Rebuild the image, re-run the failing command natively, and check the log
   for `MissingReflection`/`ERROR` before calling it fixed.
6. Commit the regenerated JSON, mentioning the scenario added.

### Standard capture suite: besu

```bash
A="-agentlib:native-image-agent=config-merge-dir=app/src/main/graal"
B=build/install/besu/bin/besu
JAVA_OPTS=$A $B --version
JAVA_OPTS=$A $B --help > /dev/null

# dev network: fresh boot + RPC traffic, then SIGTERM
JAVA_OPTS=$A $B --network=dev --data-path=/tmp/nia-dev --rpc-http-enabled &
#   ... wait for RPC, exercise eth_/net_/web3_/admin_/txpool_ calls, kill -TERM

# dev network: RESTART over the same data dir (records the Jackson *read*
# paths: version-metadata.json, database metadata — a fresh boot only
# records the write paths)
JAVA_OPTS=$A $B --network=dev --data-path=/tmp/nia-dev --rpc-http-enabled &
#   ... wait for RPC, kill -TERM

# mainnet: fresh boot + restart (dev genesis is pre-Cancun; only a
# Cancun+ genesis loads KZG, BLS12-381 and the full native-crypto checks)
JAVA_OPTS=$A $B --network=mainnet --data-path=/tmp/nia-main --p2p-enabled=false &
#   ... wait ~30s, kill -TERM; then boot it once more and kill -TERM again
```

### Standard capture suite: evmtool

```bash
A="-agentlib:native-image-agent=config-merge-dir=ethereum/evmtool/src/main/graal"
E=build/install/besu/bin/evmtool
JAVA_OPTS=$A $E --version
JAVA_OPTS=$A $E --help > /dev/null
JAVA_OPTS=$A $E benchmark sha256 EcRecover kzgPointEval \
  --warm-iterations=1 --warm-time=1 --exec-iterations=1 --exec-time=1

# EEST fixtures: files are deserialized IN FULL even when --test-name
# matches nothing, so one executed test plus parse-only passes over other
# fixture families captures the whole Jackson schema cheaply.
JAVA_OPTS=$A $E block-test <fixture.json> --test-name=<one-real-test-id>
JAVA_OPTS=$A $E block-test <other-family.json> --test-name=no-match-parse-only
```

## What belongs in `graal-manual/` instead

Entries the agent structurally cannot record, or that must survive a
wholesale re-record:

- resource lookups through the external-plugin `URLClassLoader`
  (`META-INF/besu-artifacts-catalog.json` in `PluginVerifier`) and its
  Jackson record DTOs
- resources for inputs the suite doesn't cover: genesis + profile files for
  *all* named networks, KZG trusted setups, `schema.graphqls`
- anything you fixed by hand and want to keep regardless of future
  re-records

## Known limitations (not metadata-fixable)

- External plugin jars cannot load in a native image (runtime classloading).
- Startup logs a cosmetic `Partial or missing Besu version metadata` warning
  (no jar manifest to read).
- Throughput: no C2 JIT and (on macOS) serial GC only — the native binary
  wins on startup latency, not on sustained big-block execution. On Linux,
  add `--gc=G1`; PGO on Oracle GraalVM is the next lever.
