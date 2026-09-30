rootProject.name = "agent-model"

// `foundation` is not a module of this repo — it lives in the sibling process-model repo and
// arrives through a Gradle composite build. That keeps one definition of the shared contracts
// (ProcessStage/BlockObserver, ResourcePool, SchedulingPriority) without publishing a jar or
// merging the repos into a monorepo. Agents here hold Blocks and observe them through those
// contracts, so this library needs the vocabulary without needing any concrete Block.
//
// Requires process-model to be checked out alongside this repo. If it is somewhere else, point
// this at it or set -Dprocess.model.dir=... rather than copying the sources.
includeBuild(providers.systemProperty("process.model.dir").getOrElse("../process-model"))

include("core", "integration-test", "examples")
