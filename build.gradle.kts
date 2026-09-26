import net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.yaml:snakeyaml:2.7")
    }
}

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    id("de.eldoria.plugin-yml.bukkit") version "0.9.0"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "me.emirtemur"
version = "1.0.4"

val okaeriVersion = "6.1.0-beta.4"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://storehouse.okaeri.eu/repository/maven-public/")
    // Last and Paper only, so a stale local artifact can't make local builds differ from CI.
    mavenLocal {
        content {
            includeGroup("io.papermc.paper")
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("eu.okaeri:okaeri-configs-yaml-bukkit:$okaeriVersion")

    // Config tests run Okaeri with Bukkit's YAML without a server.
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// plugin.yml is generated from here; there is no plugin.yml in the resources.
bukkit {
    name = "RingOut"
    main = "me.emirtemur.ringout.RingOutPlugin"
    apiVersion = "1.21"
    version = project.version.toString()
    authors = listOf("emirtemur")
    description = "Last one standing inside the ring wins."

    commands {
        register("ringout") {
            description = "RingOut minigame commands."
            usage = "/<command> <join|leave|list|menu|create|delete|setcenter|setlobby|radius|slices|build|start|stop|sethub|createworld|reload>"
            aliases = listOf("ro")
        }
    }

    permissions {
        register("ringout.play") {
            description = "Join and leave RingOut games, list the arenas and open the arena menu."
            default = Permission.Default.TRUE
        }
        register("ringout.admin") {
            description = "Set up and control RingOut arenas."
            default = Permission.Default.OP
        }
        register("ringout.bypass") {
            description = "Keep your inventory and game mode on server join and change the hub (for building). " +
                "Joining a game still resets you."
            default = Permission.Default.OP
        }
    }
}

// Fails the build on broken YAML in the bundled resources, so a typo in a menu can never ship
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
        filteringCharset = "UTF-8"
    }

    // Only the shadow jar (with Okaeri inside) is the plugin; a plain jar next to it would just
    // be the wrong file to copy.
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        // Okaeri is not on the server, so it ships inside the jar under our own package.
        relocate("eu.okaeri", "me.emirtemur.ringout.libs.okaeri")
        // No minimize(): Okaeri resolves config classes by reflection.
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("1.21.11")
    }
}
