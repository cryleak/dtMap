import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.3.0"
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
    id("maven-publish")
}

version = project.property("mod_version") as String + "+" + project.property("minecraft_version") as String
group = project.property("maven_group") as String
val imgui_version = project.property("imgui_version")

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25
val isMac = OperatingSystem.current().isMacOsX
val isArm64 = setOf("aarch64", "arm64").contains(System.getProperty("os.arch"))

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}

loom {
    accessWidenerPath = file("src/main/resources/dtmap.accesswidener")

    runConfigs.named("client") {
        isIdeConfigGenerated = true
        vmArgs.addAll(
            arrayOf(
                "-Dmixin.debug.export=true",
                "-Ddevauth.enabled=true",
                "-Ddevauth.account=main",
                "-XX:+AllowEnhancedClassRedefinition",
                "-javaagent"
            )
        )
    }

    runConfigs.named("server") {
        isIdeConfigGenerated = false
    }
}

repositories {
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
    mavenLocal()

    maven ( url = "https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1" )
    maven ( url = "https://repo.hypixel.net/repository/Hypixel" )

    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
    }
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    // mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    //mappings(loom.officialMojangMappings())
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    val includeImplementation = fun(str: String) {
        implementation(str)
        include(str)
    }

    arrayOf(
        "io.github.spair:imgui-java-binding:$imgui_version",
        "io.github.spair:imgui-java-lwjgl3:$imgui_version",
        "io.github.spair:imgui-java-natives-windows:$imgui_version",
        "io.github.spair:imgui-java-natives-linux:$imgui_version",
        "io.github.spair:imgui-java-natives-macos:$imgui_version",

        "net.hypixel:mod-api:1.0.1"
    ).forEach(includeImplementation)

    compileOnly("maven.modrinth:sodium:mc1.21.11-0.8.6-fabric") { isTransitive = false }

    includeImplementation("org.reflections:reflections:0.10.2")
    includeImplementation("org.javassist:javassist:3.29.2-GA")

    implementation("com.google.code.gson:gson:2.10.1")
    runtimeOnly("me.djtheredstoner:DevAuth-fabric:1.2.2")

    if (isMac) {
        val lwjglFreetypeNatives = if (isArm64) "natives-macos-arm64" else "natives-macos"
        minecraftRuntimeLibraries("org.lwjgl:lwjgl-freetype:3.3.6")
        minecraftRuntimeLibraries("org.lwjgl:lwjgl-freetype:3.3.6:$lwjglFreetypeNatives")
    }

    implementation("maven.modrinth:iris:${property("iris")}")
}

if (isMac) {
    configurations.named("minecraftRuntimeLibraries") {
        //workaround for missing freetype natives on macOS
        exclude(group = "org.lwjgl", module = "lwjgl-freetype")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version")!!,
            "loader_version" to project.property("loader_version")!!,
            "kotlin_loader_version" to project.property("kotlin_loader_version")!!
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
