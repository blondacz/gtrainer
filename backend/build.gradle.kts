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
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.gtrainer.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "--enable-native-access=ALL-UNNAMED")
}

// Bundle only frozen packet fixtures, never benchmark captures or reference outputs.
tasks.processResources {
    // Explicitly prebuilt isolated UI; never includes the personal dashboard bundle.
    from("../frontend/dist-development") { into("development-web") }
    from("../benchmarks/connected-review/cases-v2.json") {
        into("development-review")
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("runDevelopmentReview") {
    group = "application"
    description = "Opt-in loopback synthetic connected-review launcher (separate from the normal app)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.gtrainer.DevelopmentReviewLauncher")
    jvmArgs(application.applicationDefaultJvmArgs)
}
