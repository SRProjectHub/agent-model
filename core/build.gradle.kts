// agent-model's substrate. Same dependency posture as the two sibling libraries, and for the
// same reasons — see process-model's core/build.gradle.kts for the full rationale:
//
//   - repast.simphony.core is `compileOnly`, because this library is dropped into a real
//     Repast application that already has it; a second copy invites classpath conflicts.
//   - the colt / indriya / saf.core.runtime family is bundled as `implementation`, because
//     Schedule and RandomHelper touch them at class-init time regardless of what our own
//     source imports.
//
// Unlike the siblings, Repast is also a real `testImplementation` dependency here: agents live
// in a Context and most tests have to build one, so almost nothing in this module can be tested
// against a bare tick source the way a scheduler-free Block can.
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
)

dependencies {
    // Resolved through the composite build in settings.gradle.kts to the sibling repo's
    // :foundation project. `api`, not `implementation`: an agent holds Blocks and re-fires what
    // they emit, so ProcessStage, BlockObserver, ResourcePool and SchedulingPriority are part of
    // this library's own public surface and consumers need them on their compile classpath.
    api("io.github.srprojecthub.processmodel:foundation:1.0.0")

    compileOnly(files(rootProject.file("vendor/repast.simphony.core-2.11.0.jar")))
    implementation(files(bundledRepastUtilJars.map { rootProject.file("vendor/$it") }))

    implementation("org.apache.logging.log4j:log4j-1.2-api:2.17.2")
    implementation("org.apache.logging.log4j:log4j-core:2.17.2")

    testImplementation(files(rootProject.file("vendor/repast.simphony.core-2.11.0.jar")))
    testImplementation(files(bundledRepastUtilJars.map { rootProject.file("vendor/$it") }))
}
