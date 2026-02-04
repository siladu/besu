# RocksDB Secondary Instance for SLOAD Performance - Analysis & Recommendations

## Goal
Evaluate using RocksDB's read-only secondary instance feature to improve SLOAD performance in Besu's Bonsai storage mode.

## Current Status: IMPLEMENTED BUT NOT RECOMMENDED FOR PRODUCTION

The secondary instance feature has been implemented and benchmarked, but **does not provide performance benefits** and introduces **consistency risks**.

## Consistency Analysis

### Intra-Block Consistency: SAFE
Within a single block, reads always see prior writes due to in-memory caching:
- `BonsaiWorldStateUpdateAccumulator.storageToUpdate` caches all pending writes
- Read path checks accumulator FIRST, only falls back to RocksDB if not found
- RocksDB is never hit for data written in the current block

### Inter-Block Consistency: RISKY
The secondary instance reads the **base state** at the start of each block. If stale:
- Block execution starts with incorrect base state
- State root won't match expected value
- Block fails validation (sync) or produces invalid block (production)

### Operations Using Secondary Instance
ALL state reads go through `OptimisticRocksDBColumnarKeyValueStorage.get()`:
- SLOAD → `ACCOUNT_STORAGE_STORAGE` segment
- BALANCE → `ACCOUNT_INFO_STATE` segment
- EXTCODE* → `ACCOUNT_INFO_STATE` + `CODE_STORAGE` segments

## Benchmark Results

| Configuration | Avg Time | Throughput |
|---------------|----------|------------|
| Memory storage | ~159ms | ~628 MGas/s |
| RocksDB (no secondary) | ~254ms | ~393 MGas/s |
| RocksDB + secondary (sync per read) | ~297ms | ~336 MGas/s |
| RocksDB + secondary (periodic 10ms) | ~264ms | ~378 MGas/s |

**Conclusion: Secondary instance adds 4-17% overhead, no benefit.**

## Why No Performance Improvement?

1. **Sync overhead**: `tryCatchUpWithPrimary()` has inherent cost
2. **No concurrent write pressure**: Benchmark runs sequentially (write, then read)
3. **Secondary benefits require**: concurrent read/write workloads with lock contention

## Architecture Reference

## Read Path During Block Execution

```
Block N starts processing
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  Read base state (block N-1) from RocksDB           │ ◄── Secondary used HERE
│  - Account balances, nonces                         │     (potentially stale!)
│  - Storage slot values                              │
│  - Contract code                                    │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  Execute transactions (in-memory accumulator)       │
│  - Writes go to BonsaiWorldStateUpdateAccumulator   │
│  - Reads check accumulator FIRST, then RocksDB      │ ◄── Intra-block safe
│  - No RocksDB writes during execution               │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  worldState.persist(blockHeader)                    │
│  - All accumulated writes flush to RocksDB (primary)│
│  - Secondary instance doesn't see these yet!        │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  tryCatchUpWithPrimary() called (periodic, 100ms)   │
│  - Secondary now sees block N's state               │
└─────────────────────────────────────────────────────┘
```

## Key Code Locations

| File | Line | Purpose |
|------|------|---------|
| `OptimisticRocksDBColumnarKeyValueStorage.java` | 127-140 | Decision point: secondary vs primary |
| `BonsaiWorldStateUpdateAccumulator.java` | 539-567 | In-memory cache check before RocksDB |
| `AbstractBlockProcessor.java` | 495 | `worldState.persist()` - writes to RocksDB |
| `RocksDBSecondaryInstance.java` | 137-149 | Secondary instance read implementation |

## Recommendations

### Option 1: Remove Secondary Instance (Recommended)
- The feature adds complexity without measurable benefit
- Consistency risks outweigh potential gains
- Revert the changes or keep behind experimental flag

### Option 2: Sync at Block Boundaries Only
If pursuing further, modify to sync ONLY between blocks:
```java
// In block processor, after persist():
if (storage instanceof OptimisticRocksDBColumnarKeyValueStorage rocksStorage) {
    rocksStorage.syncSecondaryWithPrimary();
}
```
This ensures secondary always has the latest committed state before next block.

### Option 3: Use for Read-Only Queries Only
Route only JSON-RPC read queries (eth_call, eth_getBalance, etc.) to secondary, keeping block execution on primary. This is more complex but safer.

## Files Modified (Current Implementation)

| File | Status |
|------|--------|
| `plugins/rocksdb/.../RocksDBSecondaryInstance.java` | NEW |
| `plugins/rocksdb/.../OptimisticRocksDBColumnarKeyValueStorage.java` | Modified |
| `ethereum/referencetests/.../BlockchainReferenceTestCaseSpec.java` | Modified |
| `ethereum/evmtool/.../DataStoreModule.java` | Modified |
| `ethereum/evmtool/.../BlockchainTestSubCommand.java` | Modified |
| `ethereum/evmtool/.../EvmToolComponent.java` | Modified |
| `ethereum/evmtool/.../EvmToolCommandOptionsModule.java` | Modified |
| `ethereum/evmtool/build.gradle` | Modified |

## Known Issue: SIGSEGV on Real Node

**Status: CRASHES on production nodes**

When running with `-Dbesu.rocksdb.useSecondary=true` on a real node, a segfault occurs:
```
SIGSEGV (0xb) at pc=...
C  [librocksdbjni...so]  rocksdb::GetSstInternalUniqueId(...)+0x15c
```

### Root Cause Analysis

1. **Timing race condition**: Secondary instance is opened and calls `tryCatchUpWithPrimary()` while the primary is still processing WAL ("Processing WAL..." message appears just before crash)

2. **In `RocksDBSecondaryInstance.java` line 120**: Constructor calls `tryCatchUpWithPrimary()` immediately, which races with primary's WAL recovery

3. **DBOptions lifecycle**: The `DBOptions` object is created locally in constructor and may be garbage collected while still needed

## Fix Plan

### Fix 1: Lazy initialization of secondary instance

**File:** `plugins/rocksdb/.../OptimisticRocksDBColumnarKeyValueStorage.java`

Instead of initializing in constructor, initialize lazily on first read:

```java
private volatile RocksDBSecondaryInstance secondaryInstance;
private volatile boolean secondaryInitAttempted = false;
private final boolean useSecondary;

// In constructor - just store the flag, don't initialize yet
this.useSecondary = Boolean.getBoolean(USE_SECONDARY_PROPERTY);

// In get() method - lazy init
if (useSecondary && !secondaryInitAttempted) {
    synchronized (this) {
        if (!secondaryInitAttempted) {
            secondaryInitAttempted = true;
            initializeSecondaryInstance(configuration, trimmedSegments);
        }
    }
}
```

### Fix 2: Keep DBOptions alive in RocksDBSecondaryInstance

**File:** `plugins/rocksdb/.../RocksDBSecondaryInstance.java`

```java
// Add as instance field
private final DBOptions dbOptions;

// In constructor - store instead of local variable
this.dbOptions = new DBOptions()
    .setCreateIfMissing(false)
    .setMaxOpenFiles(-1)
    .setInfoLogLevel(InfoLogLevel.WARN_LEVEL);

// In close() method - close the options
@Override
public void close() throws IOException {
    readOptions.close();
    columnHandles.forEach(ColumnFamilyHandle::close);
    secondaryDb.close();
    dbOptions.close();  // ADD THIS
}
```

### Fix 3: Remove tryCatchUpWithPrimary() from constructor

**File:** `plugins/rocksdb/.../RocksDBSecondaryInstance.java`

```java
// REMOVE this line from constructor (line 120):
// tryCatchUpWithPrimary();

// Let the periodic sync in get() handle catching up
```

## Verification

After fixes, test on real node:

```bash
# Build
./gradlew installDist -x test

# Deploy to test node and run with:
JAVA_OPTS="-Dbesu.rocksdb.useSecondary=true" ./bin/besu ...

# Watch logs for:
# - No SIGSEGV
# - "Initializing RocksDB secondary instance" message
# - Normal block processing
```
