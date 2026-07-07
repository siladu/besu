# Hand-curated GraalVM native-image metadata for the `besu` binary

`../graal/reachability-metadata.json` is regenerated wholesale by the
native-image tracing agent (see the repo notes on the GraalVM build).
Entries the agent cannot observe belong here instead, e.g.:

- resource lookups made through the external-plugin `URLClassLoader`
  (`META-INF/besu-artifacts-catalog.json` in `PluginVerifier`)
- resources for code paths not exercised during agent capture
  (genesis files / profiles for networks other than the ones booted
  under the agent, KZG trusted setups, GraphQL schema)

Both directories are passed to native-image via
`graalvmNative.binaries.main.configurationFileDirectories` in the root
`build.gradle`.
