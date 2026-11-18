plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    jacoco
}

group = "org.snippet"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
    // PrintScript (la implementación del lenguaje). GitHub Packages pide token incluso para leer:
    // en CI lo pone el workflow; localmente, GITHUB_TOKEN=$(gh auth token).
    maven {
        url = uri("https://maven.pkg.github.com/GarroteSantiago/PrintScript")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
        content { includeGroup("org.printscript") }
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.printscript:toolchain:2.0.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// META-INF/build-info.properties: `/versions` informs the PrintScript version actually on the
// classpath as `parserVersion` (US-18). The jars carry no version of their own, so it's taken
// from the resolved dependency graph here.
springBoot {
    buildInfo {
        excludes = setOf("time")
        properties {
            additional.put(
                "printscriptVersion",
                provider {
                    configurations.runtimeClasspath
                        .get()
                        .incoming.resolutionResult.allComponents
                        .mapNotNull { it.moduleVersion }
                        .first { it.group == "org.printscript" && it.name == "toolchain" }
                        .version
                },
            )
        }
    }
}
