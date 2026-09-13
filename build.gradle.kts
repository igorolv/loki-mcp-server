plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "ru.it_spectrum.ai.loki.mcp"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.ai.mcp.server)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { enabled = false }

tasks.bootJar {
    archiveFileName.set("loki-mcp-server.jar")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.bootJar)
    systemProperty("mcp.test.jar", tasks.bootJar.get().archiveFile.get().asFile.absolutePath)
}

val integrationTest by sourceSets.creating
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(integrationTest.implementationConfigurationName, sourceSets.main.get().output)
    add(integrationTest.implementationConfigurationName, libs.testcontainers)
}
tasks.register<Test>("integrationTest") {
    description = "Opt-in isolated Loki 2.6.1 and 3.6.0 compatibility tests; requires Docker."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}
