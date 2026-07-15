plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    // AUTISM Client API is supplied as a local jar in libs/ (NOT published to a remote repo).
    // Drop autism-<version>.jar into libs/ — the flatDir repo resolves the `autism` coordinate
    // (com.autismclient:autism:<version>) to libs/autism-<version>.jar by file name. See BUILD.md.
    flatDir { dirs("libs") }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    mavenCentral()
}

dependencies {
    // Mirrors the AUTISM Client build: official Mojang mappings are implicit for 26.1.2, so there is no
    // explicit `mappings(...)` line and dependencies use plain `implementation`.
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)

    // The AUTISM Client API — resolved from libs/autism-<version>.jar via the flatDir repo above.
    implementation(libs.autism)

    // Unit testing. NOTE: fabric-loader-junit was tried first (per the usual Fabric guide) but its global
    // JUnit LauncherSessionListener boots the Fabric loader (Knot) for every test and fails with "No game
    // providers present" — loom does not expose a Minecraft game-provider on this project's TEST classpath
    // (the AUTISM API is a plain flatDir `implementation`, not a `modImplementation`). The pure-logic tests we
    // can actually write don't need the loader/MC bootstrap, so we use plain JUnit 5.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // Gradle 9 no longer bundles the JUnit Platform launcher — it must be on the test runtime classpath.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// FabricClientGameTest: a `src/gametest` source set that runs scripted tests inside a real client + world.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "boss-pvp-test"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

// Turns an exact Minecraft version (e.g. "26.1.2") into a patch-compatible range ("~26.1") so the addon
// keeps loading across patch releases instead of pinning one exact build.
fun toMinecraftCompat(version: String): String {
    val m = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?$""").matchEntire(version)
        ?: return version
    val (year, drop, _) = m.destructured
    return "~$year.$drop"
}

tasks {
    test {
        useJUnitPlatform()
    }

    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get(),
            "mc_compat" to toMinecraftCompat(libs.versions.minecraft.get())
        )
        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}
