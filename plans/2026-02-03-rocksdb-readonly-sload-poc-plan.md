# RocksDB Secondary Instance for SLOAD Performance - Implementation Plan

## Goal
Use RocksDB's read-only secondary instance feature to improve SLOAD performance in Besu's Bonsai storage mode by routing reads to a dedicated secondary instance with its own block cache.

## Background

### Current Architecture
- Bonsai uses `OptimisticTransactionDB` via `OptimisticRocksDBColumnarKeyValueStorage`
- SLOAD path: `SLoadOperation` → `BonsaiWorldState` → `BonsaiWorldStateKeyValueStorage` → `BonsaiFlatDbStrategy.getFlatStorageValueByStorageSlotKey()` → `RocksDBColumnarKeyValueStorage.get()` (line 411)
- The `get()` method calls `getDB().get(safeColumnHandle(segment), readOptions, key)`

### RocksDB Secondary Instance Benefits
- Dedicated read path with its own block cache
- No lock contention with write operations on primary
- Can catch up with primary asynchronously via `tryCatchUpWithPrimary()`

### Key Discovery: Block-test Storage Issue
**Important:** `BlockchainReferenceTestCaseSpec.buildWorldStateArchive()` (line 78-120) **always** creates an `InMemoryKeyValueStorageProvider`. The `--key-value-storage rocksdb` option in `EvmToolCommandOptionsModule` is **not wired** to the block-test command.

To benchmark with RocksDB, we must modify `BlockchainReferenceTestCaseSpec` to accept an injectable storage provider.

## Implementation Plan

### Phase A: Wire RocksDB to block-test command

#### Step A1: Modify BlockchainReferenceTestCaseSpec to accept storage provider

**File:** `ethereum/referencetests/src/main/java/org/hyperledger/besu/ethereum/referencetests/BlockchainReferenceTestCaseSpec.java`

Add overloaded method to accept a `KeyValueStorageProvider`:
```java
public ProtocolContext buildProtocolContext(
    final MutableBlockchain blockchain,
    final KeyValueStorageProvider storageProvider) {
  return new ProtocolContext.Builder()
      .withBlockchain(blockchain)
      .withWorldStateArchive(
          buildWorldStateArchive(
              Stream.of(candidateBlocks).filter(CandidateBlock::isExecutable).count(),
              blockchain,
              storageProvider))
      .withConsensusContext(new ConsensusContextFixture())
      .build();
}
```

Modify `buildWorldStateArchive()` to accept and use the storage provider.

#### Step A2: Create RocksDB storage provider for evmtool

**File:** `ethereum/evmtool/src/main/java/org/hyperledger/besu/evmtool/DataStoreModule.java`

Add a method to provide a `KeyValueStorageProvider` that can be injected into the block-test:
```java
@Provides
@Singleton
KeyValueStorageProvider provideKeyValueStorageProvider(...) {
  if ("rocksdb".equals(keyValueStorageName)) {
    return new RocksDBKeyValueStorageProvider(...);
  }
  return new InMemoryKeyValueStorageProvider();
}
```

#### Step A3: Update BlockchainTestSubCommand to use injected storage

**File:** `ethereum/evmtool/src/main/java/org/hyperledger/besu/evmtool/BlockchainTestSubCommand.java`

Inject and use the `KeyValueStorageProvider` when building the protocol context.

### Phase B: Add RocksDB Secondary Instance Support

#### Step B1: Create RocksDBSecondaryInstance wrapper class

**File:** `plugins/rocksdb/src/main/java/org/hyperledger/besu/plugin/services/storage/rocksdb/segmented/RocksDBSecondaryInstance.java`

Create a wrapper class that:
- Opens a RocksDB secondary instance via `RocksDB.openAsSecondary()`
- Manages column family handles for each segment
- Provides `tryCatchUpWithPrimary()` method to sync with primary
- Handles cleanup on close

Key considerations:
- Secondary instances require `max_open_files = -1`
- Need a separate directory for secondary's info log (e.g., `{dbPath}/secondary`)
- Must use same column family descriptors as primary

#### Step B2: Modify OptimisticRocksDBColumnarKeyValueStorage

**File:** `plugins/rocksdb/src/main/java/org/hyperledger/besu/plugin/services/storage/rocksdb/segmented/OptimisticRocksDBColumnarKeyValueStorage.java`

Changes:
1. Add optional `RocksDBSecondaryInstance secondaryInstance` field
2. Check system property `besu.rocksdb.useSecondary` to enable
3. Initialize secondary instance after primary is opened
4. Call `secondaryInstance.tryCatchUpWithPrimary()` after primary initialization

#### Step B3: Override get() method to route reads to secondary

Override the `get()` method from base class:
```java
@Override
public Optional<byte[]> get(final SegmentIdentifier segment, final byte[] key) {
  if (secondaryInstance != null) {
    return secondaryInstance.get(segment, key);
  }
  return super.get(segment, key);
}
```

#### Step B4: Add sync method and close handling

- Add `syncSecondaryWithPrimary()` method for block boundaries
- Modify `close()` to also close the secondary instance

## Files to Modify

| File | Change |
|------|--------|
| `ethereum/referencetests/.../BlockchainReferenceTestCaseSpec.java` | Accept injectable storage provider |
| `ethereum/evmtool/.../DataStoreModule.java` | Provide KeyValueStorageProvider |
| `ethereum/evmtool/.../BlockchainTestSubCommand.java` | Use injected storage |
| `plugins/rocksdb/.../segmented/RocksDBSecondaryInstance.java` | **NEW** - Secondary instance wrapper |
| `plugins/rocksdb/.../segmented/OptimisticRocksDBColumnarKeyValueStorage.java` | Add secondary instance support |

## Quick Test Approach

```bash
# Build
./gradlew installDist

# Baseline (RocksDB without secondary)
./build/install/besu/bin/evmtool block-test \
  --key-value-storage rocksdb \
  --data-path /tmp/besu-test \
  ~/Documents/eest-benchmark-fixtures/2026-01-24_c3813a5_fixtures/blockchain_tests/benchmark/compute/instruction/storage/*

# With secondary instance
JAVA_OPTS="-Dbesu.rocksdb.useSecondary=true" \
./build/install/besu/bin/evmtool block-test \
  --key-value-storage rocksdb \
  --data-path /tmp/besu-test \
  ~/Documents/eest-benchmark-fixtures/2026-01-24_c3813a5_fixtures/blockchain_tests/benchmark/compute/instruction/storage/*
```

## Important Caveats

1. **Secondary instance limitations:**
   - Requires `max_open_files = -1` (keeps all file descriptors open)
   - Snapshot reads (`ReadOptions.snapshot`) are not supported on secondary
   - Column families created after secondary opens are not visible

2. **Consistency:**
   - Secondary reads may be slightly stale until `tryCatchUpWithPrimary()` is called
   - For block execution, call sync between blocks

3. **Memory:**
   - Secondary has its own block cache (benefit: dedicated read cache)
   - May need to tune cache sizes

## Verification

1. Run block-test benchmarks with and without secondary instance
2. Compare:
   - Total execution time
   - MGas/s throughput
   - Read latency metrics (already tracked)
3. Verify correctness by checking test results match
