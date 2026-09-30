// End-to-end scenarios driving a real Repast Context and Schedule. Depends on process-model's
// `core` as well as this repo's, since proving that an agent genuinely holds Blocks means holding
// real ones — an Activity with a Buffer, not a stub.
//
// flow-model is deliberately absent for now. The plan's integration scenario wants one facility
// holding a continuous node too, which would mean a second includeBuild and process-model
// arriving through two paths at once; that is worth doing carefully rather than as scaffolding.
repositories {
    flatDir { dirs(rootProject.file("vendor")) }
}

val bundledRepastUtilJars = listOf(
    "colt-1.2.0-no_hep.jar",
    "indriya-2.0.2.jar",
    "si-quantity-2.0.1.jar",
    "si-units-2.0.1.jar",
    "systems-common-2.0.2.jar",
    "unit-api-2.0.jar",
    "uom-lib-common-2.0.jar",
    "saf.core.runtime.jar",
    // AbstractContext.iterator() chains subcontext iterators through this; required the
    // moment anything touches a Context, which for this library is immediately.
    "collections-generic-4.01.jar",
)

dependencies {
    testImplementation(project(":core"))
    testImplementation("io.github.srprojecthub.processmodel:core:1.0.0")

    // Unlike core's own unit tests, these drive a full run, so Repast is a real dependency.
    testImplementation(files(rootProject.file("vendor/repast.simphony.core-2.11.0.jar")))
    testImplementation(files(bundledRepastUtilJars.map { rootProject.file("vendor/$it") }))
    testImplementation("org.apache.logging.log4j:log4j-1.2-api:2.17.2")
    testImplementation("org.apache.logging.log4j:log4j-core:2.17.2")
}
