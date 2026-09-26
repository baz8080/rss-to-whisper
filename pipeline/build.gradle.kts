plugins {
    application
}

application {
    mainClass.set("com.rsstowhisper.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

dependencies {
    // RSS parsing
    implementation("com.rometools:rome:2.1.0")
    implementation("com.rometools:rome-modules:2.1.0")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON & YAML config (Jackson)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("org.slf4j:slf4j-api:2.0.17")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.3")
}

// Recorded in every transcript's whisper_run, so a pair can be traced to the code that wrote it.
val gitVersion =
    providers.exec {
        commandLine("git", "describe", "--always", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }

tasks.jar {
    manifest { attributes("Implementation-Version" to gitVersion.getOrElse("")) }
}
