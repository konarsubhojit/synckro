# Architecture review and optimization plan

Status: **review complete; follow-up work planned**. This document records the
current architecture, the highest-value improvement opportunities found in the
code, and a safe order for addressing them. The changes below are proposals;
this review does not change runtime behavior.

## Current shape

Synckro is an Android application organized into UI, domain, data, provider, and
dependency-injection packages. The platform-free `:core:domain` module contains
sync models and policies, while the app module owns persistence, SAF access,
providers, WorkManager, and UI (`README.md`, “Module / package layout”).

## Findings

### 1. Keep sync decisions separate from Android orchestration

`app/.../domain/sync/SyncEngine.kt` coordinates a run, but also imports Android
`Uri`, Room DAOs, data repositories, `SyncWorker`, and the fake provider
([SyncEngine.kt:3-26](../app/src/main/java/com/synckro/domain/sync/SyncEngine.kt#L3)).
`SyncOpApplier` similarly combines provider/file I/O, DAO persistence, conflict
storage, and event logging
([SyncOpApplier.kt:3-15](../app/src/main/java/com/synckro/domain/sync/SyncOpApplier.kt#L3)).
This makes the main orchestration harder to test and evolve independently of
Android and persistence.

**Plan (priority: high):** incrementally extract pure sync planning and decision
logic into `:core:domain`; keep SAF, Room, provider calls, and WorkManager in
the app layer behind narrow interfaces. Start with characterization tests for
existing decisions and preserve the current sync results and persistence
ordering. Do not move platform-dependent code into `:core:domain`.

### 2. Make large local-index reconciliation scale safely

`LocalFsEnumerator.enumerate()` loads every indexed row for a pair into memory,
builds a complete filesystem snapshot and path set, then reconciles the index
([LocalFsEnumerator.kt:145-147](../app/src/main/java/com/synckro/data/local/fs/LocalFsEnumerator.kt#L145),
[LocalFsEnumerator.kt:171-177](../app/src/main/java/com/synckro/data/local/fs/LocalFsEnumerator.kt#L171),
[LocalFsEnumerator.kt:252-293](../app/src/main/java/com/synckro/data/local/fs/LocalFsEnumerator.kt#L252)).
The stale-row SQL binds every seen path into one `NOT IN` expression
([Daos.kt:732-767](../app/src/main/java/com/synckro/data/local/dao/Daos.kt#L732)).
On older supported Android SQLite versions, sufficiently large trees may exceed
the host-parameter limit; the actual threshold should be verified on the
minimum supported API level.

**Plan (priority: high):** add a large-tree regression test above the platform
parameter limit, then replace the unbounded path-list reconciliation with a
transactional strategy that scales on API 26+ (for example, a staging table or
another bounded SQL design). Preserve atomic reconciliation: a failed or
incomplete SAF traversal must not delete previously indexed rows. Benchmark
peak memory and scan/reconciliation time before and after; retain lazy hashing
of unchanged files.

### 3. Bound caches and avoid repeated permission snapshots

`SyncEngine` is a singleton and retains scope filters in a
`ConcurrentHashMap` ([SyncEngine.kt:62-73](../app/src/main/java/com/synckro/domain/sync/SyncEngine.kt#L62)).
`scopeFiltersFor()` adds a cache entry for each distinct filter configuration,
with no eviction or invalidation
([SyncEngine.kt:490-508](../app/src/main/java/com/synckro/domain/sync/SyncEngine.kt#L490)).
Repeatedly editing pair filters during one process can therefore grow this
cache for the lifetime of the process.

Separately, `SyncPairRepository.observeAll()` recomputes the full persisted SAF
grant set for every emitted pair list
([SyncPairRepository.kt:32-39](../app/src/main/java/com/synckro/data/repository/SyncPairRepository.kt#L32),
[SyncPairRepository.kt:169-180](../app/src/main/java/com/synckro/data/repository/SyncPairRepository.kt#L169)).
Home state combines this flow with other frequently changing state
([HomeViewModel.kt:147-154](../app/src/main/java/com/synckro/ui/screens/home/HomeViewModel.kt#L147)).
The repeated permission enumeration is avoidable work; its UI impact has not
been measured.

**Plan (priority: medium):** bound or invalidate the scope-filter cache and
exercise it with many distinct configurations. Profile pair-list updates, then
centralize or cache permission snapshots if they are material, with an
explicit refresh when SAF grants may have changed (for example, after returning
from system folder selection or on app resume).

### 4. Measure real sync work before optimizing it

The `:benchmark` module currently measures startup timing. Its
`firstSyncColdStart()` scenario launches an empty-app experience and does not
seed a pair or wait for a sync run to complete
([SyncBenchmark.kt:77-99](../benchmark/src/main/java/com/synckro/benchmark/SyncBenchmark.kt#L77)).
These benchmarks therefore cannot establish the cost of local enumeration,
remote planning, or file transfers.

**Plan (priority: high; prerequisite to performance tuning):** extend the
macrobenchmark with a deterministic seeded pair and a completion signal for an
actual sync. Measure scan/planning and apply/transfer separately where
practical, including representative small and large trees. Record device/API,
file count, bytes, and elapsed time; use real-device results for decisions
rather than treating emulator timings as representative.

### 5. Separate worker execution from scheduling code

`SyncWorker.kt` contains the worker as well as `InstantCandidateResolver` and
`SyncScheduler` ([SyncWorker.kt:101](../app/src/main/java/com/synckro/data/worker/SyncWorker.kt#L101),
[SyncWorker.kt:1243-1269](../app/src/main/java/com/synckro/data/worker/SyncWorker.kt#L1243)).
This is not an observed runtime defect, but it makes scheduler policy and
worker lifecycle changes harder to review in isolation.

**Plan (priority: medium):** move the resolver and scheduler to focused files
without changing their public behavior. Keep scheduling constraints, input
data, and backoff policy covered by the existing scheduler tests.

## Suggested order and acceptance criteria

1. Establish large-tree and real-sync benchmark baselines; add the local-index
   scale regression test.
2. Fix bounded reconciliation while retaining transactionality and the SAF
   failure behavior.
3. Bound/invalidate the filter cache; measure permission-snapshot recomputation
   before changing its lifecycle.
4. Extract sync decisions behind testable, platform-free boundaries, preserving
   current behavior.
5. Split worker and scheduler source files as a behavior-preserving cleanup.
6. Re-run unit tests, `ktlintCheck`, `assembleDebug`, and `lintDebug`; compare
   representative benchmark results before accepting performance changes.

No optimization should weaken SAF permission/error handling, sync cancellation
semantics, delta-token persistence ordering, or the platform-free boundary of
`:core:domain`.
