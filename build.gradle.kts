plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.era"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

// No patch pins any more. Boot 4.1.2 manages Tomcat 11.0.x, Jackson 3.1.x and a
// current Postgres driver, all above the versions pinned for the Boot 3.5 CVEs.
// The upgrade itself is driven by Spring Framework 6.2 being EOL: CVE-2026-47884
// and CVE-2026-47890 have no fix in that line.

dependencies {
    // Deliberately lean: no JPA/Hibernate. See docs/adr/0002-jdbcclient-over-jpa.md
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Boot 4 split autoconfiguration out into per-technology modules: without
    // this one, Flyway is on the classpath but never runs, and the app starts
    // against an empty schema.
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test> { useJUnitPlatform() }
