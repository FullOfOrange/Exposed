repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    gradleApi()
    implementation("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.jvm.gradle.plugin", "1.9.10")
    implementation("com.avast.gradle", "gradle-docker-compose-plugin", "0.17.4")
    implementation("io.gitlab.arturbosch.detekt", "detekt-gradle-plugin", "1.21.0")
}

plugins {
    `kotlin-dsl` apply true
}

gradlePlugin {
    plugins {
        create("testWithDBs") {
            id = "testWithDBs"
            implementationClass = "org.jetbrains.exposed.gradle.DBTestingPlugin"
        }
    }
}
