import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.yaml:snakeyaml:2.3")
    }
}

plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "me.emirtemur"
version = "1.0.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // Last and Paper only, so a stale local artifact can't make local builds differ from CI.
    mavenLocal {
        content {
            includeGroup("io.papermc.paper")
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Fails the build on broken YAML in the bundled resources, so a typo in config.yml can never ship
// in a jar that "builds fine". Besides syntax errors and duplicate keys it rejects odd key names:
// a missing space in `key:"value"` still parses, but turns part of the value into a key.
val validateYaml = tasks.register("validateYaml") {
    val yamlFiles = fileTree("src/main/resources") { include("**/*.yml") }
    val marker = layout.buildDirectory.file("validateYaml.ok")
    inputs.files(yamlFiles)
    outputs.file(marker)
    doLast {
        // ':' is allowed for namespaced keys like `minecraft:knockback`; a silently split line
        // always contains a space, so it is still caught.
        val keyPattern = Regex("[A-Za-z0-9_.:-]+")
        fun checkKeys(node: Any?, path: String, file: String) {
            when (node) {
                is Map<*, *> -> node.forEach { (key, value) ->
                    val name = key.toString()
                    if (!keyPattern.matches(name)) {
                        throw GradleException("Suspicious key in $file at '$path': \"$name\" (missing space after ':'?)")
                    }
                    checkKeys(value, if (path.isEmpty()) name else "$path.$name", file)
                }
                is List<*> -> node.forEachIndexed { i, value -> checkKeys(value, "$path[$i]", file) }
            }
        }
        yamlFiles.forEach { file ->
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
            val root = try {
                Yaml(options).load<Any?>(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                throw GradleException("Invalid YAML in ${file.name}: ${e.message}", e)
            }
            checkKeys(root, "", file.name)
        }
        marker.get().asFile.writeText("ok")
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        dependsOn(validateYaml)
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    runServer {
        minecraftVersion("1.21.11")
    }
}
