# vendor/

Repast Simphony is not published to Maven Central and ships no standalone jars in its
source repository, so the jars this project compiles/runs against are vendored here
manually rather than resolved from a remote repository. Only this README is tracked in
git — the `*.jar` files themselves are gitignored.

## Populating this directory

Run `scripts/fetch-vendor-jars.sh` from the repo root. It downloads the official Repast
Simphony v2.11.0 update-site release, extracts the specific plugin jars this project
needs, pulls a couple of third-party jars out of `repast.simphony.core`'s own bundled
`lib/` (Repast's OSGi bundles embed their third-party dependencies inside themselves,
not as sibling files in the update site), and writes everything here with stable
filenames (version qualifiers stripped) so the Gradle build's `flatDir` references don't
need to track exact release timestamps.

The script is idempotent/re-runnable and requires network access to
`github.com/Repast/repast.simphony/releases`. It is a one-time manual setup step — the
Gradle build itself assumes no network access and never fetches these automatically.

## What's here and why

- `repast.simphony.core-2.11.0.jar` — the only Repast module this library's `core`
  compiles against (`compileOnly`, not bundled — see `core/build.gradle.kts`, and note
  below on repackaging). Provides `ISchedule`, `ScheduleParameters`, `RunEnvironment`,
  `Schedule`, `Context`, `RandomHelper`, etc.
- `colt-1.2.0-no_hep.jar` — extracted from inside `repast.simphony.core`'s own
  `lib/colt-1.2.0-no_hep.jar` (it is not a separate top-level plugin in the update
  site). Backs `RandomHelper`'s `cern.jet.random` distributions, used for
  interarrival/service-time sampling.
- `indriya-2.0.2.jar`, `si-quantity-2.0.1.jar`, `si-units-2.0.1.jar`,
  `systems-common-2.0.2.jar`, `unit-api-2.0.jar`, `uom-lib-common-2.0.jar` — the
  "indriya" units-of-measurement library family. `Schedule` touches these at
  class-init time (its `getTickCountInTimeQuantity()`-style API), even though core's
  public scheduling API never surfaces them directly. **Confirmed load-bearing by
  actually running a test against the vendored jars, not from the manifest alone** —
  `repast.simphony.core`'s `Bundle-ClassPath` lists them, but that alone doesn't tell
  you which ones are *actually* touched at runtime by the APIs this library uses.
- `saf.core.runtime.jar` — extracted from inside `repast.simphony.runtime`'s own
  `lib/saf.core.runtime.jar` (a *different* plugin than `repast.simphony.core`).
  Provides `simphony.util.messages.MessageCenter`, which `RandomHelper`'s static
  initializer needs. `repast.simphony.core`'s manifest lists
  `Require-Bundle: repast.simphony.runtime` — this turned out to be load-bearing after
  all for this one class, again confirmed by running a test, not by inspection.
- `repast.simphony.dataLoader-2.11.0.jar` — only used by the `examples` module, for
  `ContextBuilder<T>`. Not a dependency of `core` at all.

All of `colt`/the indriya family/`saf.core.runtime` are kept as `implementation`
dependencies of `core` (not `compileOnly`), even though nothing in this library's own
source imports most of them directly — they're needed the instant `RandomHelper` or
`Schedule` is touched by *anyone*, including this library's own code, so bundling them
defensively means this library's scheduling/randomness always works regardless of
whether a given consumer's classpath happens to be complete. This is a different
tradeoff than `repast.simphony.core` itself (kept `compileOnly` specifically to avoid
two copies of that large, stateful framework coexisting on a consumer's classpath) —
these are small, stable utility jars where a possible duplicate is low-risk.

Deliberately **not** vendored: everything Excel/XML/velocity/physics-related that
`repast.simphony.core` bundles internally (poi, xmlbeans, dom4j, velocity, hsqldb,
opencsv, jbullet, stack-alloc) and all of `libs.ext` (batik/jogl/java3d — GUI/3D
libraries) — none of it is reachable from the headless scheduler/context APIs this
library actually uses.

**Repackaging note**: Repast's plugin jars are OSGi bundles whose actual `.class` files
live under a `bin/` prefix inside the jar (per their own
`Bundle-ClassPath: bin/,...`), which a plain (non-OSGi) Java classpath does not
understand. `fetch-vendor-jars.sh` repackages `repast.simphony.core` and
`repast.simphony.dataLoader` into normal flat jars with that prefix stripped (and their
now-invalid code-signing entries dropped) before writing them here — nested jars like
`colt`/`saf.core.runtime` don't need this, they're already flat internally.
