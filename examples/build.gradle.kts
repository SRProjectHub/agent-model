// Wiring illustrations: what a real Repast model's ContextBuilder does with these agents.
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
    implementation(project(":core"))
    implementation("io.github.srprojecthub.processmodel:core:1.0.0")

    compileOnly(files(rootProject.file("vendor/repast.simphony.core-2.11.0.jar")))
    compileOnly(files(rootProject.file("vendor/repast.simphony.dataLoader-2.11.0.jar")))
    implementation(files(bundledRepastUtilJars.map { rootProject.file("vendor/$it") }))

    testImplementation(files(rootProject.file("vendor/repast.simphony.core-2.11.0.jar")))
    testImplementation(files(rootProject.file("vendor/repast.simphony.dataLoader-2.11.0.jar")))
}
