plugins {
    java
    pmd
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

// Static analysis for dead/unused code (semantic, not grep). Report-only for now
// (isIgnoreFailures) so it surfaces findings without breaking the build; flip to
// false to enforce. Rules live in config/pmd/dead-code.xml.
pmd {
    toolVersion = "7.19.0"
    isConsoleOutput = true
    isIgnoreFailures = true
    ruleSets = emptyList()   // ignore PMD's noisy default set; use only ours
    ruleSetFiles = files("config/pmd/dead-code.xml")
}

group = "com.ckemere"
version = "0.1.0"

// Paper 26.1+ requires Java 25 (enforced via Gradle module metadata)
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.2.build.60-beta")
    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test {
        useJUnitPlatform()
        maxHeapSize = "2g"
    }

    runServer {
        minecraftVersion("26.2")
        // Pin the Paper build. Without a pin, run-paper silently upgrades to
        // the latest beta on its update-check TTL, changing the server under
        // the dev world between sessions. Bump deliberately.
        build(60)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
