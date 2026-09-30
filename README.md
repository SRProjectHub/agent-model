# agent-model

An abstract agent substrate for [Repast Simphony](https://repast.github.io/) models: agent
identity, containment, typed links between agents, a read-only inspection contract, and mediated
interaction. Third sibling to `process-model` (discrete-event Blocks) and `flow-model` (continuous
flow), and the layer that gives those Blocks an owner.

**Status**: scaffolding only. The composite build against `process-model` resolves and is covered
by `CompositeBuildSmokeTest`; no substrate is implemented yet. Milestones, design rationale and
the decisions behind them live outside this repo for now.

## What this is not

- **Not a material or resource model.** No mass, quantity or composition. An agent holds Blocks;
  what flows through them is the model's business.
- **Not an ordering or trading library.** Orders, bids, auctions and pricing are a layer above
  this one, and the reason this library exists is to make that layer possible without folding it
  into `process-model`.
- **Not a lifecycle state machine.** This library knows join, wire, unwire and leave. What
  "commissioning" or "retired" mean is the model's vocabulary, expressed over agent state plus the
  Block control surface `process-model` already provides.
- **Not spatial.** No grids, continuous space, geography or movement, and no network generators.

## Requirements

- A real Repast Simphony application providing `repast.simphony.core` on its own runtime classpath
  (depended on here as `compileOnly`, to avoid bundling a second copy).
- `process-model` checked out alongside this repo, or pointed at with `-Dprocess.model.dir=...`.
  It arrives through a Gradle composite build, so an absent sibling fails at configuration time
  rather than mid-build.
- Java 8+ at runtime (this library targets Java 8 bytecode); a JDK 17 or newer to build with.

## Building

```bash
./scripts/fetch-vendor-jars.sh   # once — Repast is not on Maven Central; vendor/*.jar is gitignored
./gradlew build
```

## Modules

| Module | What it holds |
|---|---|
| `core` | the library |
| `integration-test` | scenarios driving a real Repast `Context` and `Schedule` |
| `examples` | `ContextBuilder` wiring illustrations |

`foundation` is deliberately *not* a module here — it lives in `process-model` and arrives through
the composite build, so there is exactly one definition of the shared contracts.
