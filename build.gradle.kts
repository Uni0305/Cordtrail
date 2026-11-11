plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    alias(libs.plugins.shadow)
}

group = "com.github.uni0305"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.bundles.logging)
    implementation(libs.clickhouse.client)
    implementation(libs.jackson.databind)
    implementation(libs.jda) {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.github.uni0305.cordtrail.CordtrailBotKt",
                    "com.github.uni0305.cordtrail.CordtrailBot*"
                )
            }
        }
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier = ""
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.github.uni0305.cordtrail.CordtrailBot"
    }
}

tasks.test {
    useJUnitPlatform()
}
