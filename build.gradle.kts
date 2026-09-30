plugins {
    java
}

allprojects {
    group = "io.github.srprojecthub.agentmodel"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile> {
        // Match Repast Simphony's own Bundle-RequiredExecutionEnvironment: JavaSE-1.8, and
        // match the sibling libraries so all three are droppable into the same application.
        // Using `release` (not just source/targetCompatibility) so the compiler also
        // validates against the Java 8 API surface, not just syntax.
        options.release.set(8)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.10.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
