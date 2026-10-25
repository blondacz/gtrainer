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
    systemProperty("gtrainer.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

tasks.register<JavaExec>("runDevelopmentReview") {
    group = "application"
    description = "Opt-in loopback synthetic connected-review launcher (separate from the normal app)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.gtrainer.DevelopmentReviewLauncher")
    jvmArgs(application.applicationDefaultJvmArgs)
}

tasks.register<JavaExec>("runSyntheticIntakePilot") {
    group = "application"
    description = "Opt-in isolated handwritten-witness intake pilot"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.gtrainer.SyntheticIntakePilot")
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("runLiveIntakePilot") {
    group = "application"
    description = "Opt-in bounded inspector for an admitted synthetic intake capture"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.gtrainer.LiveIntakePilot")
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val localUiCheckResources = layout.buildDirectory.dir("local-ui-check-resources")
val stageLocalUiCheckResources = tasks.register<Sync>("stageLocalUiCheckResources") {
    from("../frontend/dist") { into("web") }
    into(localUiCheckResources)
    doFirst { require(file("../frontend/dist/index.html").isFile) { "Build the normal frontend before the synthetic UI check" } }
}

tasks.register<JavaExec>("runLocalDescriptiveUiCheck") {
    group = "verification"
    description = "Opt-in normal-app browser check with invented records and fake owned transport only"
    dependsOn(tasks.testClasses, stageLocalUiCheckResources)
    classpath = files(localUiCheckResources) + sourceSets.test.get().runtimeClasspath
    mainClass.set("com.gtrainer.LocalDescriptiveUiCheck")
    workingDir = rootProject.projectDir
    jvmArgs(application.applicationDefaultJvmArgs)
}

tasks.register<JavaExec>("exportOwnerUatContextFeasibility") {
    group = "verification"
    description = "Export four invented normal descriptive prompts without source, provider or runtime requests"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.gtrainer.OwnerUatContextFeasibilityExport")
    workingDir = rootProject.projectDir
    jvmArgs(application.applicationDefaultJvmArgs)
}
