plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

val ktorVersion = "3.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.gtrainer.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}

tasks.test {
    useJUnitPlatform()
}
