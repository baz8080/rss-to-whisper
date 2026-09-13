plugins {
    // Applied in this order so that the Quarkus plugin sees kotlin("jvm") already
    // present and can configure kotlin-allopen / kotlin-noarg cleanly.
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.quarkus")
}

val quarkusVersion = "3.33.1" // LTS

dependencies {
    // Quarkus BOM — manages all io.quarkus:* artifact versions.
    // platform() (not enforced) lets the Kotlin version pinned by the root
    // build take precedence over whatever the BOM declares.
    implementation(platform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))

    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-arc")
    // quarkus-resteasy-reactive was renamed to quarkus-rest in Quarkus 3.9+
    implementation("io.quarkus:quarkus-rest")
    // JSON serialisation for /api/*. quarkus-rest alone has no body writer for
    // anything but text, so without this the API routes return 500.
    implementation("io.quarkus:quarkus-rest-jackson")

    // Thymeleaf — no official Quarkiverse extension exists; used as a plain
    // library with a hand-rolled CDI producer (see TemplateEngineProducer.kt).
    implementation("org.thymeleaf:thymeleaf:3.1.2.RELEASE")

    // SQLite JDBC — not in Quarkus BOM; used directly without Agroal
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // Episode summaries are third-party RSS content rendered as raw HTML, so
    // they are sanitised against an allow-list before reaching the template.
    implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.mockk:mockk:1.13.12")
}
