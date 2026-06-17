# Design Doc: Scriptpacks — Server-Side Fabric Scripting Platform

## Goal

A server-side-only Fabric mod that turns Bun/TypeScript "scriptpacks" into first-class,
installable content — an advanced fusion of datapacks and resourcepacks plus live logic.

A scriptpack is a directory:

```
scriptpacks/<namespace>/
  scriptpack.json          manifest (required)
  src/main.ts              entrypoint, run by Bun
  resources/...            author's resource pack (assets/<ns>/..., maybe assets/minecraft/...)
  data/...                 author's datapack (data/<ns>/...)
conflict-resolve.txt       server-owner-authored conflict resolutions (repo root of scriptpacks/)
```

At startup the mod discovers all scriptpacks, loads each `data/` as a datapack, merges all
`resources/` into one server resource pack, and spawns one Bun process per scriptpack running
`src/main.ts`. `/scriptpacks reload` restarts processes, reloads datapacks, and rebuilds the
resource pack.

---

## Three invariants the agent must not violate

Every design decision derives from these. Getting any one wrong breaks the system in a way
that is hard to diagnose.

### 1. The item registry is frozen after startup

New item *types* cannot be registered at runtime, and a server-side-only mod cannot register
new types at all without desyncing vanilla clients. **Custom items are vanilla item types
carrying data components** (custom name, lore, `item_model`, enchantments, attribute
modifiers, `custom_data` for logic). Vanilla clients render these with no client code. There
is no other option under server-side-only. See "Custom items."

### 2. Minecraft mutates world state only on the main server thread

Live game state is read/written only on the main thread, reached via
`MinecraftServer.execute(Runnable)`, which runs tasks when the server thread drains its queue
(tick-coupled, 20 TPS). **A handler dequeued onto the main thread cannot be safely
interrupted** — an infinite loop, blocking call, or sleep there freezes the server
permanently, and no timeout prevents it. Mitigated only by keeping work off the main thread
(the snapshot/read tier) and auditing what must run on it. See "Execution model."

### 3. Client visuals require a publicly-hosted resource pack

`item_model`/`custom_model_data` resolves entirely client-side. The server cannot push model
bytes through gameplay packets — it can only hand the client a **URL + SHA-1** to download a
pack over HTTP. This means the mod must host the merged pack on a **public** interface,
distinct from the loopback RPC port. This is the one part of the system outside the
loopback/trust boundary. See "Resource pack subsystem."

---

## Architecture: the mod is the orchestrator

The mod is not a passive RPC server. It:

- discovers and validates scriptpacks,
- loads datapacks and builds/hosts the merged resource pack,
- spawns and supervises one Bun subprocess per scriptpack,
- runs a loopback RPC server those subprocesses call for game operations,
- owns the `/scriptpacks reload` lifecycle.

```
                          ┌────────────────────────── Fabric Mod (JVM) ──────────────────────────┐
                          │                                                                       │
  Bun proc (ns A) ──RPC(loopback)──┐                                                              │
  Bun proc (ns B) ──RPC(loopback)──┼──► RPC server ──► main-thread dispatch / snapshot reads      │
  Bun proc (ns C) ──RPC(loopback)──┘                                                              │
        ▲                          │     Supervisor: spawn / log / detect-exit / restart          │
        │ spawned & supervised ────┘     Resource builder: scan → conflict-check → merge → host    │
        │                                Datapack loader: load each data/ via vanilla semantics    │
        │                                                                                          │
  Client ◄──URL+SHA-1── Public pack HTTP server (separate port, serves only the merged .zip) ──────┘
```

---

## Process model

**One Bun subprocess per namespace.** A crash is isolated to and attributable to one
scriptpack; each restarts independently on reload.

Spawn `bun run scriptpacks/<ns>/src/main.ts` with environment carrying: the namespace, the
loopback RPC port, a per-process token (routing/attribution only — not security), and
read-only paths to the scriptpack's own `resources/` and `data/`.

Supervisor responsibilities: spawn; capture stdout/stderr into per-namespace logs; detect
exit; on reload, kill-then-respawn. **Crash policy: leave dead and log, do not auto-restart**
(a script that crashes on startup would busy-loop). Off-thread; never block the tick thread on
subprocess teardown.

---

## scriptpack.json

```json
{
  "name": "coolpack",
  "displayName": "Cool Pack",
  "author": "...",
  "repo": "https://...",
  "description": "...",
  "enforceSafe": false
}
```

| Field | Required | Notes |
|---|---|---|
| `name` | yes | **Must equal the namespace directory name** exactly. Error on mismatch. The directory name is the canonical namespace; `name` is a redundant declaration that must agree. |
| `displayName` | no | Human-facing label. |
| `author` | yes | |
| `repo` | no | |
| `description` | no | |
| `enforceSafe` | no | Defaults to `false` (not safe) when omitted. See below. |

**Validation timing:** parse and validate *all* manifests first, before spawning any process
or building any pack. Fail fast and attribute clearly. A missing or malformed
`scriptpack.json`, or a `name` ≠ directory mismatch, is a hard startup error naming the pack.

---

## Resource pack subsystem

### Conflict model (narrow by construction)

If each scriptpack confines assets to its own namespace (`assets/<ns>/...`) and data to
`data/<ns>/...`, **inter-scriptpack collisions are structurally impossible** — different
namespaces, different paths. `coolpack:sword` and `otherpack:sword` never collide.

Conflicts arise **only** when two scriptpacks write the same path, which in practice means
two packs both override a *shared* namespace — almost always `assets/minecraft/...` (vanilla
textures, GUI, font, lang). That shared-namespace overlap is the entire conflict surface.

Detection: build a `path → [contributing namespaces]` map across all `resources/`. Any path
with ≥2 contributors is a conflict.

### conflict-resolve.txt (winner-only, machine-readable)

One line per conflicting path; the value is the winning namespace. **No merge strategies** —
the winner's file is taken whole, all others for that path dropped. (Merging JSON would
silently synthesize a third file nobody authored — a bug source. Whole-file replacement is
predictable.)

```
assets/minecraft/textures/gui/title/background.png = coolpack
assets/minecraft/lang/en_us.json = otherpack
```

- Exact path `=` exact namespace. No comment syntax, no keywords.
- A conflicting path **with no entry** → **hard error, abort the build.** Never silently pick.
- A resolve entry for a path that is **no longer contested** (stale) → **warn, do not error**
  (erroring would make pack removal fragile).

### Generated error message

On an unresolved conflict, the abort message emits ready-to-paste candidate lines so the
operator copies rather than authors syntax:

```
ERROR: unresolved resource conflict at assets/minecraft/textures/gui/title/background.png
  contributors: coolpack, otherpack
  add ONE of the following to conflict-resolve.txt:
    assets/minecraft/textures/gui/title/background.png = coolpack
    assets/minecraft/textures/gui/title/background.png = otherpack
```

### enforceSafe (per-pack, checked in isolation)

`enforceSafe: true` means **this scriptpack must not contribute any file under a shared
(non-namespaced) namespace** — i.e. nothing under `assets/minecraft/...`. If it does, startup
errors naming the violating pack.

Key properties:

- Checked **per-pack at scan time, before** the cross-pack conflict map is built. A safe pack
  that touches `minecraft:` fails on its own merits, regardless of what else is installed.
- It is an author's promise about their own pack ("I override nothing vanilla, I am
  conflict-incapable"), enforced by the mod. A server owner can trust an `enforceSafe` pack to
  never participate in a conflict.
- It does **not** resolve conflicts. Two non-safe packs editing `minecraft:` still conflict
  and still need `conflict-resolve.txt`. `enforceSafe` only prevents one pack from ever being
  a party to one.

### Build + host pipeline

Startup and reload:

1. Scan all `resources/` → build `path → contributors` map.
2. Run `enforceSafe` checks (per-pack, may abort).
3. Apply `conflict-resolve.txt`; any unresolved conflict → **abort** with generated error.
4. Assemble the merged `.zip`.
5. Compute SHA-1.
6. Host it on the **public** HTTP server (separate from loopback RPC); serve only the zip.
7. Set as the server resource pack with that URL + hash.

**Must complete before players can join** (during init), or early joiners get no visuals.

**Hosting is exposed.** The URL handed to clients must be the server's externally-reachable
address, configured by the owner. This endpoint serves only the read-only merged zip. It is
the sole component outside the loopback boundary.

**Require-pack posture is the owner's product decision:** `require-resource-pack=true`
disconnects clients that lack the pack (guarantees visuals, excludes pack-less clients);
`false` degrades gracefully (custom items show their base vanilla appearance; all
`custom_data` and server-side behavior still work).

---

## Datapack subsystem

Load each scriptpack's `data/` as a **separate datapack** using **vanilla datapack
semantics** — vanilla already defines load order and tag-merging. **Datapacks are out of
scope for the conflict/resolve subsystem**; `conflict-resolve.txt` and `enforceSafe` apply to
resource packs only. Do not force errors on datapack overlaps; let vanilla resolve them.

Reload via `server.reloadResources(...)` (main thread, returns a future — bridge it).

---

## Custom items (server-side, option 1)

Custom items are **vanilla item types carrying data components**. No new registry entries, no
client code for existence or behavior.

- **Appearance/identity:** components — `custom_name`, `lore`, `item_model` (namespaced model
  id, e.g. `coolpack:fireblade`), `enchantments`, `attribute_modifiers`, etc.
- **Logic hook:** `custom_data` (author-defined NBT the scriptpack reads back).
- **Behavior:** implemented server-side by hooking events (`UseItemCallback`,
  `AttackEntityCallback`, `UseBlockCallback`, tick handlers), inspecting `custom_data`, and
  acting. The client never participates in behavior.
- **Visuals:** the namespaced `item_model` resolves against the merged resource pack
  client-side. Namespaced model ids mean **no cross-scriptpack model-id collisions** — the
  same namespacing that protects assets protects model references.

Item-spec wire format mirrors the vanilla `ItemStack` codec so deserialization is free
(`ItemStack.CODEC` with a `RegistryOps`):

```json
{
  "id": "minecraft:paper",
  "count": 1,
  "components": {
    "minecraft:item_model": "coolpack:fireblade",
    "minecraft:custom_name": "...",
    "minecraft:custom_data": { "coolpack_kind": "fireblade" }
  }
}
```

---

## Execution model (RPC server)

Mechanism is binary (off-thread or main-thread); three intents:

- **Snapshot read** — off-thread, against a per-tick immutable snapshot. Fast, killable.
- **Live read** — main-thread, marshalled. For collection-backed state too large/unsafe to
  snapshot (inventory, entities-in-region, arbitrary block). Blocking, tick-bound latency, but
  does not mutate.
- **Mutation** — main-thread, marshalled.

Two handler types, enforced by the type system. A `ReadHandler` is never handed a
`MinecraftServer`, so it structurally cannot touch live state (closes the off-thread-access
footgun) and can be hard-timeout-killed safely. `MutatingHandler` runs live on the main thread,
can mutate, **cannot be killed** — keep tiny and audited.

```java
@FunctionalInterface
interface ReadHandler {     // off-thread, snapshot only, killable
    JsonElement invoke(WorldSnapshot snapshot, JsonObject params) throws Exception;
}
@FunctionalInterface
interface MutatingHandler { // main-thread, live access, NOT killable
    JsonElement invoke(MinecraftServer server, JsonObject params) throws Exception;
}
```

**Bias: prefer `ReadHandler`.** Live reads and mutations share the marshalled mechanism;
document "live read" as a legitimate use of the mutating tier.

### Snapshot

Once per tick (`ServerTickEvents.END_SERVER_TICK`, main thread), copy hot player state into
immutable per-entry `JsonObject`s in a `ConcurrentHashMap`. Read handlers serve directly
off-thread: sub-millisecond, lock-free, ≤1 tick (~50ms) stale. **Never read live state
off-thread** — collection-backed state can throw `ConcurrentModificationException` or return
torn data; the snapshot is safe only because the copy happens on the tick thread.

### Dispatcher guards (defend against bugs, not adversaries)

- **Request size cap** before reading body — prevents OOM.
- **Concurrency cap** (`Semaphore`) → 429 when exceeded — bounds the main-thread queue.
- **Per-handler exception isolation** — a throwing handler returns an error and never
  propagates into the dispatcher or tick loop.
- **Full traces returned to the caller** (trust model — the caller is a scriptpack the owner
  installed; detail aids debugging).

### Watchdog (detection only)

Main thread writes `lastTickNanos` each tick; a daemon thread alarms if a tick hasn't advanced
past a threshold, logging the in-flight mutating method name. It **cannot** kill the stuck
task — it turns "server froze" into "handler X froze it," the difference between a fast and a
multi-hour debug.

---

## Operation catalog

`S` = snapshot read · `L` = live read (main-thread) · `M` = mutation (main-thread)

**Players:** `listPlayers` (S), `getPlayerData` (S), `getInventory` (L),
`givePlayerItem` (M), `removeItem`/`clearInventory` (M), `teleportPlayer` (M),
`setGameMode` (M), `setHealth`/`setHunger`/`setXp` (M), `applyEffect`/`clearEffect` (M),
`setAttribute` (M), `sendMessage`/`sendTitle` (M).

**World:** `getBlock` (L), `setBlock` (M), `fillBlocks` (M), `getEntities` (L),
`spawnEntity` (M), `getTime` (L) / `setTime` (M), `setWeather` (M), `setGameRule` (M),
`setDifficulty` (M), `playSound`/`spawnParticles` (M), `setWorldBorder` (M).

**Content:** `buildItemStack` (S, pure construction), `applyComponents` (S, pure),
`reloadData` (M).

**Fallback:** `runCommand` (M) — keep for everything not worth wrapping.

### Common commands → prefer the Fabric call

Direct calls buy structured args/returns and skip brigadier parse/permission overhead. They
are **not** a latency win (mutations marshal either way). Go direct where it buys structure;
keep `runCommand` where the API is ugly or absent.

| Command | Direct call | Tier |
|---|---|---|
| `/give` | `player.giveItemStack(stack)` | M |
| `/tp` | entity/player teleport API | M |
| `/setblock` | `world.setBlockState(pos, state, flags)` | M |
| `/fill` | loop `setBlockState` over `BlockBox` | M |
| `/summon` | `EntityType.create` + `world.spawnEntity` | M |
| `/effect` | `addStatusEffect`/`removeStatusEffect` | M |
| `/gamemode` | `player.changeGameMode(...)` | M |
| `/time set` | `world.setTimeOfDay(t)` | M |
| `/weather` | `world.setWeather(...)` | M |
| `/difficulty` | `server.setDifficulty(d, force)` | M |
| `/gamerule` | gamerules API | M |
| `/playsound`,`/particle` | `world.playSound`/`spawnParticles` | M |
| `/xp` | `addExperience`/`addExperienceLevels` | M |
| `/scoreboard` | `server.getScoreboard()` | M |
| `/attribute` | attribute-instance API | M |
| `/data get` | direct getters | L |

**Keep as `runCommand`** (no clean/stable API, not worth reimplementing): `/execute` (do not
reimplement the conditional/context engine), `/function`, `/loot`, `/clone`, `/forceload`,
`/datapack enable|disable` (use the reload path instead).

---

## `/scriptpacks reload`

Build-then-swap, atomic, never partial-apply:

1. Build the new merged pack into a **staging location** and run all conflict/`enforceSafe`
   checks. **If anything fails, abort the entire reload** — old state stays live, error to the
   operator. (off-thread)
2. Kill all Bun subprocesses. (off-thread)
3. Reload datapacks via `server.reloadResources(...)`. (main thread, future)
4. Swap in the new resource pack (new SHA → re-push to clients; **this forces every client to
   re-download and shows a loading screen** — unavoidable, document it). (main thread)
5. Respawn Bun subprocesses against fresh state. (off-thread)

MC-reload steps (3–4) are main-thread; process/file work (1, 2, 5) is off-thread. Never block
the tick thread on subprocess teardown.

---

## Author SDK (a deliverable, not an afterthought)

Ship a typed TypeScript client every scriptpack codes against: the `rpc<T>` wrapper, typed
signatures for every operation, the item-spec builder, and the event-subscription surface for
custom-item behavior.

```typescript
type RpcResult<T> = { ok: true; value: T } | { ok: false; error: string };

async function rpc<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  const res = await fetch(`http://127.0.0.1:${RPC_PORT}/rpc`, {
    method: "POST",
    headers: { "X-Scriptpack": NAMESPACE, "X-Token": TOKEN },
    body: JSON.stringify({ method, params }),
  });
  const data = (await res.json()) as RpcResult<T>;
  if (!data.ok) throw new Error(data.error);
  return data.value;
}
```

(`RPC_PORT`, `NAMESPACE`, `TOKEN` injected via env by the supervisor.)

---

## Trust model

Scriptpacks are downloaded third-party code running as subprocesses with full RPC access to
the game. **They are not sandboxed.** "Trusted" means what it means for datapacks and mods
today: the server owner vouches for each one they install. The per-process token is
routing/attribution, not a security boundary. The loopback RPC port takes no auth by design.
The only exposed surface is the public pack-hosting HTTP server, which serves a read-only zip.

---

## Version-sensitivity — agent MUST verify against the target MC/Yarn mappings

**Target 1.21.4+.** Strongly recommended, because pre-1.21.4 reintroduces problems this design
relies on being solved:

- **Data components landed in 1.20.5.** Pre-1.20.5 uses raw NBT — a different code path
  entirely. Component IDs / `DataComponentTypes` constants are version-sensitive.
- **Model resolution changed in 1.21.4** — `minecraft:item_model` component + item-model
  definition files under `assets/<ns>/items/`. Pre-1.21.4 uses bare-integer
  `custom_model_data`, which forces a **global integer-allocation registry across
  scriptpacks** (the mod would have to broker ints to avoid collisions). Namespaced
  `item_model` removes that problem — a primary reason to target 1.21.4+.
- **`custom_model_data` structure changed in 1.21.4** (single int → structured form). The
  item-spec must match the target version's shape.
- **Enchantments became data-driven in 1.20.5** — `/enchant`-equivalent code differs across
  that boundary.
- **Command execution:** `CommandManager.executeWithPrefix(source, cmd)` on 1.19.3+; older
  uses `getDispatcher().execute(cmd, source)`.
- **`reloadResources`** reliably reloads recipes/loot/advancements/tags/functions; some
  registries (certain worldgen) load only at startup — verify per content type.
- **Server resource-pack API:** multi-pack support (1.20.3+) and the runtime push/`transfer`
  signatures are version-dependent. (We merge into one pack regardless — see below.)
- Teleport, `setWeather`, `getWorld`/`getServerWorld`, `getPlayerList` signatures shift.

---

## Implementation checklist

- [ ] Manifest discovery + validation (name = dir, fail-fast, attribute) before anything else.
- [ ] Per-namespace Bun supervisor: spawn, env injection, log capture, exit detection,
      leave-dead-on-crash, reload kill/respawn — all off-thread.
- [ ] Loopback RPC server: size cap, concurrency semaphore, exception isolation, full-trace
      errors, `X-Scriptpack`/`X-Token` routing.
- [ ] `ReadHandler`/`MutatingHandler` registries; `WorldSnapshot` rebuilt per END_SERVER_TICK.
- [ ] Operation catalog incl. direct-Fabric mappings for the command table.
- [ ] Custom-item support: `ItemStack.CODEC` spec decode, `custom_data` behavior hooks.
- [ ] Resource builder: scan → `enforceSafe` per-pack → conflict map → `conflict-resolve.txt`
      (winner-only, stale=warn, unresolved=abort+generated lines) → merge → SHA-1.
- [ ] Public pack HTTP server (separate port, serves only the zip); set as server pack
      before join.
- [ ] Datapack loader: each `data/` as separate datapack, vanilla semantics, no conflict logic.
- [ ] `/scriptpacks reload`: staged build, abort-on-failure, ordered swap, client re-download.
- [ ] Watchdog thread (stuck-tick detection + handler name).
- [ ] Author SDK / typed client package.
- [ ] All version-sensitive calls verified against target Yarn mappings (target 1.21.4+).

---

## Non-goals

- Sandboxing scriptpacks — they are trusted third-party code with full RPC access.
- Security against untrusted callers (no RPC auth, no capabilities, no rate limiting); the
  per-process token is attribution only.
- Hard-killing or interrupting main-thread (`MutatingHandler`) work — impossible on the JVM;
  mitigated by the read/mutate split, audit, and the detection-only watchdog.
- Registering new item *types* at runtime — impossible server-side; option 1 (component-driven
  instances of vanilla types) only.
- Datapack conflict resolution — out of scope; vanilla semantics apply.
- Merging conflicting resource files — winner-takes-whole-file only; no JSON deep-merge.
- Sub-tick read freshness — the snapshot is intentionally ≤1 tick (~50ms) stale.
- Delivering pack bytes in-band — clients download from the public URL by URL + SHA-1.
