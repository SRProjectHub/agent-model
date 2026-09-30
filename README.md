# agent-model

An abstract agent substrate for [Repast Simphony](https://repast.github.io/) models. Third sibling
to `process-model` (discrete-event Blocks) and `flow-model` (continuous flow), and the layer that
holds them.

Blocks are mechanisms with no purpose. A `Queue` does not know why it is holding anything, and
nothing in either sibling ties a set of Blocks into one thing with a name that can be built, watched,
related to others, and eventually removed. That is what an agent is here — and deliberately no more
than that.

**Status**: v1 core complete — 140 tests covering every concept below, plus end-to-end scenarios
driving a real Repast `Context` and `Schedule` with agents holding discrete Blocks and continuous
nodes at once. See [Known gaps](#known-gaps) for what is deliberately still open.

## What this is not

- **Not a material or resource model.** No mass, quantity or composition. An agent holds Blocks;
  what flows through them is the model's business, exactly as it is the model's business in the
  sibling libraries.
- **Not an ordering or trading library.** Orders, bids, auctions and pricing are a layer above this
  one. Making that layer possible without folding it into `process-model` is why this library exists,
  and putting it here instead would repeat the mistake.
- **Not a lifecycle state machine.** This knows join, wire, unwire and leave. What "commissioning" or
  "retired" mean is a model's own vocabulary, expressed over agent state plus the control surface
  (`pause`/`resume`, `open`/`close`, `isIdle`) that `process-model` already provides.
- **Not spatial.** No grids, continuous space, geography or movement, and no network generators.
- **Not a statechart engine.** Expressible as agent state over the Block control surface, and a
  statechart engine is its own library.

Names here are deliberately neutral. Nothing is borrowed from another simulator, and no identifier
implies an application this library does not model.

## Concepts

| | |
|---|---|
| `Agent` / `AbstractAgent` | identity, and the plumbing for everything below |
| `Population` | a named, typed group with its own nested context, minting its members' names |
| `AgentNetwork` | one named relation — and the channel its messages travel along |
| `Inspection` | polling many agents for a capability, read-only |
| `Mediator` | delivering messages in their own event at the same instant |
| `Membership` | joining and leaving, deferred, with departure guards |
| `Registry` | making agents of a named type, now or at a stated time |
| `ProcessVisit` | an agent passing through a chain of Blocks and coming back |

### Identity has three parts

An agent is identified by its Java **class**, a **type name**, and an **instance name** minted by the
population it belongs to:

```
class = WorkingUnit    typeName = "alpha"    getName() = "unit_group-1"
```

Several types routinely share one class, so the class alone does not say what a model means by a type.
Ordinals are never reused, so a name identifies one agent for a whole run even after it has left.

### Holding, containment, and relations are three different things

Conflating them is what makes a substrate fit one kind of model and fail the rest.

**Holding** is a reference with observation attached. An agent holds Blocks — discrete
`ProcessStage`s and continuous `FlowNode`s alike — and capability objects. Whatever a held Block
emits reaches the agent's listeners attributed to the agent, which is the aggregation
`process-model` deliberately leaves to this layer. Capabilities are *held* rather than implemented
because Java permits one superclass, and because holding composes: an agent can hold several, swap
one at runtime, and keep the implementing class private behind the type it is published under.

**Containment** is structural — exclusive, mutual, and what defines where an agent lives. It maps
onto Repast subcontexts, so a root-level `getObjects(Class)` still finds nested agents and a display
can be pointed at one population. Events from anything a contained agent holds reach its containers
too, keeping their original attribution: a listener at the top learns *which* agent's Block emitted.

**Relations** are plural, independent, and separate from both. A container holds its members without
depending on them; an overseer may watch a group it does not contain. Each is its own
`AgentNetwork` with its own direction, cardinality and message type.

### Inspection and interaction are different acts

Reading another agent is a direct call and must change nothing — the discipline `SolveContext`
states on the continuous side. Anything that may trigger an action or event in the **receiving**
agent is mediated instead, and arrives in its own event.

An agent watching **its own** held Block is neither: nobody else is involved, so those callbacks fire
synchronously inside the Block event, exactly ordered. That is how an agent acts *within* an instant
rather than only after it.

## Ordering of simultaneous events

Agent-layer work extends `process-model`'s `SchedulingPriority` bands and sits **below all of them**:

| Band | Value | What runs there |
|---|---|---|
| `COMPLETION` … `SETTLE` | 400 … 100 | Block events, from the sibling libraries |
| `INTERACTION` | 50 | mediated agent-to-agent delivery |
| `STRUCTURE` | 25 | joining and leaving, with guards |

So a message sees a settled world, and structural change happens after the instant's messages have
finished — because adding or removing agents while something is walking the population is the fault
this ordering exists to prevent. Both are plain `double`s; a model needing to interleave can.

**One Repast behaviour to know about.** An action scheduled for the tick the clock has already
reached is *silently discarded* unless something is currently executing. Sending from inside any
scheduled action is therefore fine, which is where sends normally happen. From outside one, with the
clock already advanced, use `Mediator.deliverPending()` or `Membership.applyPending()`.

## Requirements

- A real Repast Simphony application providing `repast.simphony.core` on its own runtime classpath
  (depended on here as `compileOnly`, to avoid bundling a second copy).
- `process-model` checked out alongside this repo, or pointed at with `-Dprocess.model.dir=...`, and
  `flow-model` likewise with `-Dflow.model.dir=...`. Both arrive through Gradle composite builds, so
  an absent sibling fails at configuration time rather than mid-build. `process-model` is reachable
  by two routes, since `flow-model` includes it too; Gradle deduplicates it.
- Java 8+ at runtime (this library targets Java 8 bytecode); a JDK 17 or newer to build with.

## Building

```bash
./scripts/fetch-vendor-jars.sh   # once — Repast is not on Maven Central; vendor/*.jar is gitignored
./gradlew build
```

`fetch-vendor-jars.sh` pulls two jars the sibling libraries never needed, because they are the first
things required once anything touches a Repast `Context` or a `Network` projection:
`collections-generic` (a context chains its subcontexts' iterators through it) and the two JUNG jars
(network projections are JUNG graphs underneath).

## Modules

| Module | What it holds |
|---|---|
| `core` | the library |
| `integration-test` | scenarios driving a real `Context` and `Schedule`, across both siblings |
| `examples` | `SubstrateContextBuilder`, the wiring a real Repast model does |

`foundation` is deliberately *not* a module here — it lives in `process-model` and arrives through
the composite build, so there is exactly one definition of the shared contracts.

Unlike the siblings, `core` needs Repast as a real `testImplementation` dependency: agents live in a
`Context`, so almost nothing here can be tested against a bare tick source the way a scheduler-free
Block can.

## Known gaps

Left open deliberately, not overlooked.

- **Nothing schedules a guarded departure's retry.** A refused guard leaves the request pending until
  something asks again. In continuous time there is no natural interval to pick, and only the model
  knows when the condition it is waiting on has changed — so `applyPending` is how it says so. A
  periodic retry would be a modelling decision dressed as a default.
- **An agent with no handler for a relation silently drops its messages.** That is what lets a
  relation exist for structure alone, but it also means a mistyped `onReceive` looks like a working
  send that does nothing. Quiet was chosen over strict; revisit if it bites.
- **A visit through a chain has to be wired in two places** — every Block to track passed to
  `watch`, and the chain's last Router aimed at `exit`. A chain is defined by routing lambdas that no
  library can discover, so there is nothing to infer from.
- **`Cardinality.ONE` refuses a second connection rather than replacing it**, so reassignment is an
  explicit disconnect then connect. Silent replacement would lose information; the cost is a little
  ceremony.
- **Blocks are held, never shared.** A `ResourcePool` is deliberately shared across libraries and is
  simply referenced by whichever agents use it, with no agent claiming it. A distinct "uses" relation
  would be the way to model shared holding properly, if something needs it.
- **Ordering between an agent-to-agent message and an unrelated Block event at the same instant is
  defined, but only because the Block libraries now set explicit priorities.** They did not always;
  if a future Block schedules without one, it re-enters the randomly-ordered set and agent traffic
  can no longer be placed against it. `CompositeBuildSmokeTest` pins the band values so that surfaces
  loudly.
