# GrimacZ Performance Engineering Report

Fork: GrimZ (GrimAC 2.0, commit `f5bbe9cf5`) — performance pass on branch `2.0`.
Scope: implementation-level optimization only. **No check, threshold, formula, packet-order, or detection semantic was changed.**

---

## 1. Repository Audit (Phase 3 initial deliverable)

### 1.1 Architecture

| Module | Contents |
|---|---|
| `common` | All detection logic: 568 Java sources (~60k LOC with `bukkit`), checks (`checks/impl/*`, ~40 check classes registered per player), prediction engine (`predictionengine/`), compensated world/entities/inventory (`utils/latency/`), collision system (`utils/collisions/`, `utils/nmsutil/`), managers, config, datastore client glue |
| `bukkit` | Paper/Spigot platform glue (39 files): schedulers, Bukkit events, commands (cloud), LuckPerms/PlaceholderAPI/Geyser hooks |
| `fabric/*` | Fabric platform variants (1.16.1–26.x), Loom remapped |
| `buildSrc` | Gradle convention plugins, versioning |

Build: Gradle 9.4.1, Java toolchain 21 (`--release 17`), Lombok, Spotless, shadow (bukkit). Core deps: PacketEvents 2.14.1-SNAPSHOT (packet pipeline), fastutil, HikariCP, cloud, adventure, ViaVersion API.

### 1.2 Runtime execution model

- **Packet pipeline** runs on the player's **Netty event-loop thread** via PacketEvents listeners (`CheckManagerListener.onPacketReceive` → pre-prediction checks → `MovementCheckRunner.processAndCheckMovementPacket` → `PredictionEngine.guessBestMovement`). The prediction engine runs **inline on the netty thread** (no prediction thread pool in this fork).
- **Sync tick** (`TickManager.tickSync`) on Bukkit main thread; **async tick** (`TickPermissions`, `TickInventory`, `ClientVersionSetter`, `ClearRecentlyUpdatedBlocks`) on the async scheduler.
- **Per-player state** (`GrimPlayer`, `CheckManager`, `CompensatedWorld`, `LatencyUtils`, prediction state) is thread-confined to the player's netty loop; cross-thread handoffs go through `LatencyUtils` transaction queues (synchronized) and Bukkit scheduler hops.
- **Storage** runs on LMAX Disruptor rings (bounded, drops-with-counter on overflow) fed non-blocking from netty threads — no DB I/O on hot paths (verified).

### 1.3 Critical hot paths traced

One flying packet (20–200/s per player) triggers: teleport/kb bookkeeping → `PredictionEngine.fetchPossibleStartTickVectors` → candidate-velocity generation (input transformers, 0.03 possibilities, jumps) → for **each** candidate vector: `handleStartingVelocityUncertainty` → `Collisions.collide` (full AABB world scan, per-block `CollisionData` factories) → best-vector selection → `MovementTickerPlayer.move` → post-prediction listeners. Additional per-packet scans: stepping-on-blocks flags, glitchy-block `hasMaterial`, `Phase`/`NoFall` scans, supporting-block finder.

Consequence: anything "per block per scan" is effectively **per-packet × 4–10 scans**; allocation churn is dominated by `WrappedBlockState` clones, `VectorData`/`Vector3dm`/`SimpleCollisionBox` temporaries, and `HashSet` wrapper copies.

### 1.4 Probable bottlenecks (evidence: code-path tracing; no live server in build environment)

Ranked by (impact × confidence ÷ risk):

1. **`CompensatedWorld.getBlock` clones a `WrappedBlockState` per call** — ~30–80 clones per movement packet (fluid scans, inside-blocks, climbable, friction, connecting blocks). The codebase already uses non-cloning reads in its collision scan loops, so the invariant is established.
2. **`VectorData.hashCode()` allocates a 16-slot `Object[]` + 10 boxed Booleans per HashSet op** — the prediction pipeline is HashSet-heavy (candidate vectors), so this is the single hottest pure-allocation site.
3. **`HashSet` wrapper churn in `determineCanSkipTick` call chains** — 3–6 sets/tick in `updateVelocityMovementSkipping` + `new HashSet<>(singletonList(...))` per knockback/explosion candidate.
4. **`new PredictionEngine()`/`new PredictionEngineNormal()` per vector/tick** despite the classes being stateless (upstream comment: "Takes 0.01 millis... this should be improved eventually").
5. **`DynamicConnecting.connectsTo` re-fetches `currBlock` 4–5× per fence/wall/pane per scan.**
6. **`getLavaFluidLevelAt` fetches the block above before the lava early-out.**
7. **Flag-path waste on exempted players**: `Check.flag(Verbose.Writer)` serializes verbose bytes before the disable/exempt check rejects it.
8. **Static mutable perf counters** (`predictionNanos`) written on netty threads, read by `/grim perf` without `volatile`.
9. Cross-player **`synchronized(flags)` monitor in `OffsetHandler`** wrapping the whole flag() pipeline (documented, not changed — see §7).
10. Startup: 10 synchronous YAML parses, Hikari/ownership-claim blocking on main thread (documented, not changed — see §7).

### 1.5 Memory / GC concerns

- Hot-path garbage (above) is all short-lived (TLAB-friendly), so its cost is allocation rate, not retention.
- Retention: `SuperDebug.flags[256]` static StringBuilder array (~17 MB worst case, shared across players, never cleared — pre-existing upstream issue; left untouched to avoid changing debug behavior).
- Bounded caches verified: chunk map (render-distance bound), `unackedActions` (50 cap), `EvictingQueue`s (60), `blockHistory` (drained by tick task).

---

## 2. Change Report (Deliverable B)

Commit: `perf: reduce allocation pressure and redundant world lookups in hot paths` (25 files, +278/−99).

| # | File / method | Original bottleneck | Change | Why behavior is preserved | Impact class |
|---|---|---|---|---|---|
| 1 | `utils/data/VectorData.java` `hashCode()` | `Objects.hash(...)` → 16-slot `Object[]` + 10 boxed `Boolean` per call, called on every HashSet op of the prediction pipeline | Manual 31-based accumulation; **proven identical** to `Objects.hash` (which starts `result = 1`) by differential test over 500,000 randomized states including recursive `lastVector` chains (0 mismatches). Caught and fixed two subtle divergence bugs (seed value, recursion) during development thanks to that test | Identical hash values ⇒ identical HashSet bucketing ⇒ identical iteration order (which feeds sort tie-breaking) | ~7.5× faster hash; removes the dominant per-op allocation |
| 2 | `utils/latency/CompensatedWorld.java` `getBlock` | `chunk.get(version,x,y,z)` defaults to `clone=true` ⇒ one `WrappedBlockState` clone per lookup | Added `getBlock(x,y,z,boolean clone)`; clone=true remains the default for the existing signature. Verified **read-only** callers switched to `clone=false`: `getWaterFluidLevelAt` (×2), `getLavaFluidLevelAt` (×2), `isWaterSourceBlock`, `getBlockType(double,double,double)` | PacketEvents `WrappedBlockState` getters are pure reads; the shared-state invariant is already used by the existing `section.get(..., false)` scan loops in `Collisions`. All **mutating** callers (e.g. `tickOpenable` → `setOpen` → write-back `getGlobalId`) intentionally keep `clone=true`; verified `checkIfCloneNeeded` COW semantics by decompiling the PacketEvents jar | Eliminates ~30–80 state clones per movement packet |
| 3 | `utils/latency/CompensatedWorld.java` `getLavaFluidLevelAt` | Fetched block above **before** the `!= LAVA` early-out | Moved the above-fetch after the early-out | Both fetches are side-effect-free reads; result identical for every input | Halves lookups for non-lava blocks in lava scans |
| 4 | `utils/collisions/blocks/connecting/*` (6 files) + `DynamicConnecting.connectsTo` | `currBlock` (the block at x,y,z) re-fetched by each of the 4–5 `connectsTo` calls per fence/wall/pane per scan | New `connectsTo(..., currBlock)` overload; callers fetch **once** via `getBlock(x,y,z,false)` and pass it. The conditional fetch in `DynamicCollisionWall.fetch` keeps the modern fast path fetch-free | The passed state is exactly the state the old code fetched at (x,y,z); read-only use (`getType`/`getFacing`). The position-independent `(0,0,0)` fetch path (3-arg `getMovementCollisionBox`) keeps its exact semantics because callers still fetch `getBlock(x,y,z,false)` rather than reusing the factory's block argument | 3–4 fewer world lookups + clones per connecting block per scan |
| 5 | `utils/nmsutil/Collisions.java` | `handleInsideBlocks`, bubble-column `blockAbove`, `onClimbable` cloned states | Switched to `getBlock(..., false)` | Verified read-only: `getType`/`isDrag`/property reads only; `trapdoorUsableAsLadder` verified non-mutating | Per-packet clone reduction |
| 6 | `utils/nmsutil/Collisions.java` `collide` | `ArrayList` regrowth (10→15→22→33) during box collection | Presized to 32 | Pure capacity hint; `ArrayList` semantics unchanged | Removes 2–3 array copies per collide call |
| 7 | `predictionengine/PointThreeEstimator.java` | `new PredictionEngine()` + full per-vector body inlined in the skip-tick loop; callers wrapped single vectors in fresh `HashSet`s | Extracted `evaluateSkipTickLength(data, speed, couldStep)` **verbatim**; added `determineCanSkipTick(float)` (zero-vector) and `determineCanSkipTick(float, VectorData)` (single-vector) overloads with the identical guard sequence | The zero-vector overload reproduces the empty-set path exactly, **including the `checkForGround` side effect** (`Collisions.collide` can set `uncertaintyHandler.isStepMovement`), and returns `false` exactly where `Double.MAX_VALUE < threshold` is false. Single-vector overload = loop run once | Kills 3–6 HashSet allocations/tick + engine-per-vector allocations |
| 8 | `player/GrimPlayer.java` `getPossibleVelocitiesMinusKnockback` | Defensive `new HashSet<>(possibleMovements)` copy taken even when `slimePistonBounces` is empty (the common case) | Whole loop now gated on `!uncertaintyHandler.slimePistonBounces.isEmpty()` | When the bounce list is empty the original loop adds nothing; skipping it is observationally identical. When non-empty, the copy + add-while-iterating semantics are unchanged (no CME) | One set copy per tick per player |
| 9 | `player/GrimPlayer.java` `updateVelocityMovementSkipping` | 2 `HashSet` allocations per tick for 0–2 elements | Zero/one-vector overload paths; `new HashSet<>(2)` only when both kb (or explosion) vectors exist. Local `kbPointThree`/`explosionPointThree` booleans preserved | Guard sequence runs identically in all branches (verified against the set-based path); flag/reset logic untouched | Allocation-free common case |
| 10 | `PredictionEngine`, `PredictionEngineNormal`, `PredictionEngineElytra` | Stateless engine classes instantiated per tick/per vector (10 call sites) | Hoisted to `static final INSTANCE` singletons | Verified **no instance fields** in these three classes (state lives in `PredictionEngineWater` — intentionally left per-instance) | Removes per-tick engine allocations |
| 11 | `checks/Check.java` `flag(Verbose.Writer)` (×2 overloads) | `writer.end().toByteArray()` ran **before** the `disableGrim`/exempt/experimental check that usually rejects the flag | Exemption check moved ahead of `lazyVerbose(...)` | Identical conditions to `recordFlag`; the serialized bytes were dead on that path (used only after `recordFlag` succeeds). `Writer` instances are created per flag call site and never reused | Eliminates per-flag byte[] churn for exempted/disabled players |
| 12 | `utils/nmsutil/Materials.java` `isWaterlogged` | `PacketEvents.getAPI()...getVersion().isOlderThan(V_1_13)` static chain per call in per-block paths | Hoisted to `static final SERVER_IS_PRE_1_13` | Server version immutable at runtime; same pattern as the pre-existing `Collisions.IS_FOURTEEN` | Constant-folded by JIT |
| 13 | `predictionengine/MovementCheckRunner.java` | `predictionNanos`/`longPredictionNanos` (static doubles) written on netty threads, read by `/grim perf` — no visibility guarantee | `volatile` | Read-only diagnostic counters; volatile adds visibility without changing the (pre-existing, benign) non-atomic RMW races | `/grim perf` reads are now correct |
| 14 | `utils/math/GrimMath.java` `calculateSD` | `Math.pow(x, 2)` per element | `x * x` | Bit-equivalent for squaring; method currently has **zero call sites** (dead-code utility), so no reachable behavior change | Trivial |

**Rejected after investigation (kept original code):**

- `SuperDebug.onPredictionComplete` early-return: the flag dump reads the last ~60 ticks of queue history populated on every prediction; skipping population when idle would change `/gl` debug output (observable behavior).
- `SuperDebug.flags[256]` shared static retention: fixing it would change debug-command behavior; documented as a memory concern instead.
- `VectorData` value-equality → identity semantics: the pipeline's sort/dedup behavior may rely on value equality; too risky without a full-replay harness.
- `OffsetHandler synchronized(flags)` narrowing: the flag-id allocation semantics (`(get()&255)+1`, increment-on-success, uniqueness across players) cannot be preserved lock-free without a redesign; documented in §7.
- Caching `MultiLibUtil.isExternalPlayer` per player: upstream TODO acknowledges it, but external-player status is plugin-managed and could theoretically change mid-session; deferred to maintainers (single reflective call per packet, only on 1.18+ MultiLib setups).

---

## 3. Benchmark Report (Deliverable C)

**Environment**: 4.1 GB RAM / 2 vCPU container, OpenJDK 21.0.12 (Temurin), Gradle 9.4.1, `--max-workers=1`, daemon `-Xmx1400M`. No Minecraft server could be run in this environment (insufficient RAM for a representative Paper instance + clients), so **end-to-end server metrics were not collected**. Nothing below is fabricated; unmeasured items are explicitly marked.

### 3.1 Measured

| Metric | Value | Method |
|---|---|---|
| Baseline `:bukkit:build` | 3 min 17 s (cold-ish deps) / ~35 s warm | Gradle, before changes |
| Optimized `:bukkit:build` + `:common:test` | 35 s + 16 s, **BUILD SUCCESSFUL**, spotless/check clean | Gradle, after changes |
| `VectorData.hashCode` equivalence | 500,000 randomized instances (chains depth 1–5, all 11 boolean flags, null/non-null combos): **0 hash mismatches** vs `Objects.hash` | Standalone differential test (`scripts/VectorDataHashDifferentialTest.java`), seed 42 |
| `VectorData.hashCode` throughput | old 9,225 ms vs new 1,231 ms for 20.5M hashes ⇒ **7.49×** | Plain-JVM timing harness, 1 warmup round, sink to defeat DCE. **Indicative only — not JMH** |

### 3.2 Estimated (analysis-based, NOT measured — requires in-game validation)

- `WrappedBlockState` clone elimination in fluid/inside/climbable paths: ~30–80 allocations per movement packet avoided (per player), i.e. roughly 600–1600 allocs/s/player at 20 MPS.
- HashSet wrapper + stateless-engine hoisting: ~10–30 fewer small allocations per tick per player in `updateVelocityMovementSkipping` / `determineCanSkipTick`.
- Flag-path byte[] serialization: eliminated for exempted/disabled players only.

### 3.3 Measurement limitations and required validation

1. **No live-server benchmark** (startup duration, tick-time contribution, p50/p95/p99 offset latency, allocation rate via async-profiler, GC pause stats, player-count scaling). Recommended: JFR + async-profiler `alloc` mode on a Paper 1.21.x server, 20–100 bots (e.g. ViaFakePlayers), before/after comparison, ≥3 runs each.
2. Microbenchmark is a plain harness, not JMH; it demonstrates the allocation elimination and relative speed of the new hash, not end-to-end gains.
3. JVM flags, GC choice, and hardware tuning were deliberately **not** changed (spec §9).

---

## 4. Compatibility Report (Deliverable D)

- **Plugin identity**: untouched. `plugin.yml`/`bungee.yml`/`fabric.mod.json` generation, plugin name, version scheme (`2.3.74-base` + git modifiers), commands, permissions, config keys and defaults, messages, database schemas: unchanged.
- **Public API**: no existing class/member signatures removed. Added: `CompensatedWorld.getBlock(int,int,int,boolean)`, `PointThreeEstimator.determineCanSkipTick(float)` / `(float, VectorData)`, `DynamicConnecting.connectsTo(..., WrappedBlockState)`, `PredictionEngine.INSTANCE`, `PredictionEngineNormal.INSTANCE`, `PredictionEngineElytra.INSTANCE` — purely additive.
- **Build targets**: `:common`, `:bukkit`, `:fabric/*` module list unchanged; `--release 17` baseline preserved (verified: full `:bukkit:build` green).
- **Serialization**: `VectorData` is not `Serializable` (only `Vector3dm` is, unchanged) — no persisted-format impact.
- **Integrations**: ViaVersion/ViaBackwards interplay, Floodgate/Geyser, LuckPerms, PlaceholderAPI, PacketEvents JiJ/shade packaging — untouched.

---

## 5. Concurrency Report (Deliverable E)

**Changed:** only `MovementCheckRunner.predictionNanos`/`longPredictionNanos` → `volatile` (visibility of diagnostic averages across netty threads and the command thread). No executor, lock, queue, or thread-affinity change. All Batch-1 changes are single-thread-confined (per-player netty loop) data-path refinements.

**Audited and intentionally left unchanged (risks documented for maintainers):**

1. `OffsetHandler` static `synchronized(flags)` — one global monitor across all players' flag pipelines; narrowing requires a rigorous redesign of flag-id allocation (uniqueness semantics).
2. `/grim reload` mutates `PunishmentManager.groups`, `Check.violations`, and config fields while netty threads read them (pre-existing race; fix requires immutable-snapshot swap design).
3. `LatencyUtils` per-player monitor runs arbitrary task runnables under lock — the cross-thread handoff point; safe but a contention consideration under heavy block-update load.
4. `TickInventory`/`PlayerDataManager.shouldCheck` touch Bukkit API from the async tick thread (upstream acknowledges "probably safe").
5. `GrimHistory` commands block the calling thread on `CompletableFuture.get(...)` (0.5–10 s) — UX issue, not detection-critical.
6. Boot path: `DataStoreLifecycle.start()` performs Hikari connect + ownership claim (possible `Thread.sleep` up to 20 s) + legacy migration synchronously on the main thread; `/grim reload` re-runs teardown/reinit. Both are lifecycle-sensitive and were deliberately not made asynchronous without a full correctness design.

---

## 6. Dependency Report (Deliverable F)

- **Added: none. Removed: none. Changed: none.**
- AI frameworks (`LangChain4j`, `Embabel`, `Spring AI`) were evaluated per the engineering brief: none provides a runtime capability that speeds up packet processing, prediction, collision, or JVM execution. They are **not integrated** — neither as plugin dependencies nor as build-time deps — because (a) no developer-tooling requirement exists in-repo that they would satisfy better than the existing toolchain (Gradle, Spotless, JFR), (b) they would add multi-MB transitive graphs and class-loading cost to a latency-sensitive runtime, and (c) the brief forbids runtime AI integration without a justified product requirement. All optimization analysis in this pass was produced by static tracing, decompilation (javap) of PacketEvents, and differential testing — no AI framework needed.

---

## 7. Testing Report (Deliverable G)

**Commands run** (JDK 21 toolchain at `/home/z/my-project/tools`):

| Command | Result |
|---|---|
| `./gradlew :bukkit:build -x spotlessApply -x javadoc` (baseline, pre-change) | ✅ 3m17s |
| `./gradlew :common:compileJava` (post-change) | ✅ 33s |
| `./gradlew :bukkit:build -x javadoc` (post-change; includes `spotlessApply`, `spotlessCheck`, `check`) | ✅ 35s |
| `./gradlew :common:test` (existing test suite: `VerboseTemplateAuditTest`, `V2InstanceRegistryTest`, `LeaseStartupLivenessTest`) | ✅ 16s, all pass |
| `java VectorDataHashDifferentialTest` (500k differential states + hash harness) | ✅ 0 mismatches |

**Completed validation**: compilation of all modules, formatting (spotless), existing unit tests, differential equivalence of the one change that could alter collection behavior (hashCode), per-call-site mutation audit for every `clone=false` switch (each listed in §2 with its read-only proof).

**Outstanding validation (requires a live server, cannot be executed here):**
1. Regression play-test across supported client versions (1.8–26.x) covering: normal movement, 0.03 skipped ticks, step/ stairs, water/lava, slime/honey/bed bounces, pistons, ladders/vines, fences/walls/panes, elytra, vehicles (pig/strider/horse/camel/boat), knockback/explosions, setback behavior.
2. Differential detection run: same deterministic packet traces through original vs GrimacZ builds comparing check outcomes, violation values, setbacks, alerts.
3. JFR/async-profiler allocation + latency profiles before/after under representative load.

---

## 8. Remaining Optimization Opportunities (Deliverable H)

Ranked by probable impact ÷ (complexity × regression risk):

1. **Cache/block-state-level collision-box reuse** — `CollisionData.getMovementCollisionBox`/`fetch` allocate per block per scan; `DynamicCollisionBox` re-runs its factory on every `downCast`/`isCollided` (2–9× per block). Eager resolution + immutable translated views would remove hundreds of short-lived objects per packet in fence/wall/hopper-rich areas. Complexity: high (touches every factory); needs the missing live-server differential harness first.
2. **Redundant per-packet world scans** — the stepping-on flags scan, glitchy-block `hasMaterial`, `Phase`, `NoFall`, and the supporting-block finder each rescan the player's AABB every packet. A single shared scan result per tick could cut world iteration ~2–3×. Complexity: high; must preserve exact per-check semantics.
3. **`VectorData` pipeline collections** — per-tick `HashSet` rebuilds in `getPossibleVelocities`/variant engines (`PredictionEngineWater`/`Elytra`/`RideableUtils` copies + `vector.clone()` dance). Scratching per-player reuse requires proving nothing relies on value-equality dedup. Complexity: medium-high.
4. **`OffsetHandler` global flag lock** — per-player striping or lock-free id reservation. Complexity: medium; requires the flag-id semantic redesign noted in §5.
5. **`PlayerDataManager.getPlayer` reflective `isExternalPlayer`** — one reflective call per packet on 1.18+ (upstream TODO); cache per player after verifying no mid-session status changes. Complexity: low.
6. **`PacketEntityReplication` interpolation allocations** (`LinearPositionPath`, `TrackerData`, `Vector3d` per entity-move packet) — scales with entity count; measure-first.
7. **Startup** — defer `ProxyAlertMessenger`'s three SnakeYAML server-file parses; move datastore init/ownership-claim off the main thread behind a lifecycle-safe design; make `/grim reload` non-blocking.
8. **`SuperDebug.flags[256]`** — bounded per-player debug storage instead of a shared 256-slot static array (~17 MB worst case, cross-player leakage).
9. **JVM/runtime** — collect JFR + GC baselines on representative hardware before considering any flag recommendations (none justified currently).

---

## 9. Definition-of-Success Checklist

- ✅ Detection logic, thresholds, and check semantics: unchanged (every change carries an equivalence argument in §2).
- ✅ Public identity/compatibility: unchanged (§4).
- ✅ No new dependencies (§6); no AI framework in the runtime (§6).
- ✅ Evidence-based: differential test for the only hash-semantics change; per-site mutation audits; compile+test+format gates green.
- ⚠️ Live-server performance deltas: **not claimable** without the in-game benchmark campaign in §3.3 — no numbers are claimed beyond the measured microbenchmarks and build times.
