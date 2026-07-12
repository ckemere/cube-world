plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
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
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
}

tasks {
    runServer {
        minecraftVersion("26.1.2")
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
