// SPDX-License-Identifier: GPL-3.0-only
plugins {
    java
}

if (Runtime.version().feature() != 25) throw GradleException("Run Gradle with JDK 25 (set JAVA_HOME).")

group = "com.vaultbackup"
version = "1.3.0"

// The server APIs. Both projects recommend compiling against their own API artifact, pinned here to
// exact builds whose bytes gradle/verification-metadata.xml fixes.
//   minimum: the api-version declared in plugin.yml. The release JARs are compiled against these.
//            Both lines are finished upstream, so these builds stay available and a tag stays buildable.
//   current: what Paper and Spigot ship and support today. The same sources are compiled against these
//            as a check, with every deprecation treated as an error.
// Spigot's repository keeps only the newest few builds of a version that is still being built. When
// the current Spigot build below has been removed upstream, move it to the newest build and refresh
// the verification metadata (see BUILDING.md); the release JARs do not depend on it.
val spigotApiMinimum = "org.spigotmc:spigot-api:26.2-R0.1-20260915.151451-13"
val paperApiMinimum = "io.papermc.paper:paper-api:26.2.build.135-stable"
val spigotApiCurrent = "org.spigotmc:spigot-api:26.3-R0.1-20261026.080323-10"
val paperApiCurrent = "io.papermc.paper:paper-api:26.3.build.253-stable"

repositories {
    // Each project's API comes only from its own repository, and those repositories serve nothing else.
    exclusiveContent {
        forRepository { maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "spigot" } }
        filter { includeGroup("org.spigotmc") }
    }
    exclusiveContent {
        forRepository { maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" } }
        filter {
            includeGroup("io.papermc.paper")
            // Two dependencies of the Paper API that are not on Maven Central.
            includeGroup("com.mojang")
            includeVersionByRegex("net\\.md-5", "bungeecord-chat", ".*-deprecated\\+build\\..*")
        }
    }
    mavenCentral()
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// src/main  : everything that runs on both servers, plus the Spigot implementation. Compiled against the Spigot API.
// src/paper : the Paper implementation (Adventure, Brigadier). Compiled against the Paper API.
val paper: SourceSet by sourceSets.creating
val paperTest: SourceSet by sourceSets.creating

/** A compile-time view of one API artifact, used to compile the sources once more against the current versions. */
fun apiView(name: String, notation: String): Configuration = configurations.create(name) {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
    dependencies.add(project.dependencies.create(notation))
}
val spigotCurrent = apiView("spigotCurrent", spigotApiCurrent)
val paperCurrent = apiView("paperCurrent", paperApiCurrent)

dependencies {
    // Server APIs are provided by the server at run time and are never packaged.
    compileOnly(spigotApiMinimum)
    "paperCompileOnly"(paperApiMinimum)
    "paperCompileOnly"(sourceSets.main.get().output)

    // The harnesses fake a server through the current API interfaces.
    testImplementation(spigotApiCurrent)
    "paperTestImplementation"(paperApiCurrent)
    "paperTestImplementation"(sourceSets.main.get().output)
    "paperTestImplementation"(paper.output)
}
/** Only the API JARs of a class path that also carries this project's own class directories. */
fun apiJars(configuration: String): FileCollection = configurations[configuration].filter { it.name.endsWith(".jar") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}
// Both projects ask plugins not to use deprecated API. A deprecation in either API fails the build here.
listOf(tasks.compileJava, tasks.named<JavaCompile>("compilePaperJava")).forEach { compile ->
    compile.configure { options.compilerArgs.addAll(listOf("-Xlint:deprecation,removal", "-Werror")) }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val licenseFiles = listOf("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md")

tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "revision.txt")) { expand("version" to projectVersion) }
    from(projectDir) { include(licenseFiles); into("META-INF") }
}

// The offline restore helper is a separate program: the core and restore packages only, no server API.
val restoreJar by tasks.registering(Jar::class) {
    archiveBaseName = "vaultbackup-restore"
    from(sourceSets.main.get().output.classesDirs) { include("com/vaultbackup/core/**", "com/vaultbackup/restore/**") }
    from(projectDir) { include(licenseFiles); into("META-INF") }
    manifest { attributes("Main-Class" to "com.vaultbackup.restore.RestoreMain") }
}

// The plugin: common classes, the Paper classes, and the finished helper JAR as a resource.
tasks.jar {
    archiveBaseName = "VaultBackupPro"
    from(paper.output)
    from(restoreJar) { rename { "vaultbackup-restore.jar" } }
}

val release by tasks.registering { dependsOn(tasks.jar, restoreJar) }

tasks.register("resolveCompileClasspath") {
    val files = configurations.compileClasspath.get() + apiJars("paperCompileClasspath") + spigotCurrent + paperCurrent
    doLast { files.files.forEach { println(it) } }
}

// ---- Verification -------------------------------------------------------------------------------
// The checks are standalone assertion programs rather than JUnit tests, so each can also be run by
// hand against a built JAR. JUnit's test task is intentionally disabled.
tasks.test { enabled = false }

fun harness(name: String, sourceSet: SourceSet, main: String, configure: JavaExec.() -> Unit = {}) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        dependsOn(sourceSet.classesTaskName)
        classpath = sourceSet.runtimeClasspath
        mainClass = main
        configure()
    }.also { task -> tasks.check { dependsOn(task) } }

val testSet = sourceSets.test.get()
harness("coreTest", testSet, "com.vaultbackup.core.BackupEngineTest")
harness("restoreTest", testSet, "com.vaultbackup.restore.RestoreSafetyTest") {
    dependsOn(restoreJar)
    argumentProviders.add { listOf(restoreJar.get().archiveFile.get().asFile.absolutePath) }
}
harness("startupTest", testSet, "com.vaultbackup.restore.RestoreStartupTest") {
    dependsOn(restoreJar)
    argumentProviders.add { listOf(restoreJar.get().archiveFile.get().asFile.absolutePath) }
}
harness("settingsTest", testSet, "com.vaultbackup.plugin.PluginSettingsTest") {
    args(file("src/main/resources/config.yml").absolutePath, file("src/main/resources/plugin.yml").absolutePath)
}
harness("deleteWorkflowTest", testSet, "com.vaultbackup.plugin.DeleteWorkflowTest")
harness("spigotPlatformTest", testSet, "com.vaultbackup.plugin.SpigotPlatformTest")
harness("paperPlatformTest", paperTest, "com.vaultbackup.plugin.PaperPlatformTest")

// The same sources must compile against the APIs the servers ship today, without using anything
// either project has deprecated there.
val compileAgainstCurrentSpigot by tasks.registering(JavaCompile::class) {
    group = "verification"
    source = sourceSets.main.get().java
    classpath = spigotCurrent
    destinationDirectory = layout.buildDirectory.dir("api-current/spigot")
    options.compilerArgs.addAll(listOf("-Xlint:deprecation,removal", "-Werror"))
}
val compileAgainstCurrentPaper by tasks.registering(JavaCompile::class) {
    group = "verification"
    source = paper.java
    classpath = paperCurrent + files(compileAgainstCurrentSpigot.map { it.destinationDirectory })
    destinationDirectory = layout.buildDirectory.dir("api-current/paper")
    options.compilerArgs.addAll(listOf("-Xlint:deprecation,removal", "-Werror"))
}
// Everything that executes on Paper must be free of API that Paper has deprecated. The classes in
// src/main are compiled against the Spigot API, where those warnings do not exist, so they are
// compiled once more against the current Paper API. The two Spigot-only classes are not part of that.
val paperDeprecationCheck by tasks.registering(JavaCompile::class) {
    group = "verification"
    source = sourceSets.main.get().java + paper.java
    exclude("com/vaultbackup/plugin/SpigotPlatform.java", "com/vaultbackup/plugin/BackupCommands.java")
    // The two excluded classes are still referenced by name; javac takes them from the compiled main classes.
    classpath = paperCurrent + sourceSets.main.get().output.classesDirs
    dependsOn(tasks.compileJava)
    destinationDirectory = layout.buildDirectory.dir("api-current/paper-deprecation")
    options.compilerArgs.addAll(listOf("-Xlint:deprecation,removal", "-Werror"))
}

// One JAR runs on two APIs. Every class, method and field the shipped classes reference is resolved
// against each API version: the common classes against all four, the Paper classes against Paper's.
val paperOnlyClasses = listOf("com/vaultbackup/plugin/PaperPlatform", "com/vaultbackup/plugin/PaperCommands")
val linkageCheck by tasks.registering { group = "verification" }
mapOf(
    "SpigotMinimum" to configurations.compileClasspath.get(),
    "SpigotCurrent" to spigotCurrent,
    "PaperMinimum" to apiJars("paperCompileClasspath"),
    "PaperCurrent" to paperCurrent,
).forEach { (name, api) ->
    val task = tasks.register<JavaExec>("linkageCheck$name") {
        group = "verification"
        dependsOn(tasks.jar, testSet.classesTaskName)
        classpath = testSet.runtimeClasspath
        mainClass = "com.vaultbackup.tools.ApiLinkageCheck"
        val jarFile = tasks.jar.flatMap { it.archiveFile }
        inputs.files(jarFile, api)
        argumentProviders.add {
            val onPaper = name.startsWith("Paper")
            listOf(if (onPaper) "all classes on $name" else "common classes on $name", jarFile.get().asFile.absolutePath,
                "com/vaultbackup/", if (onPaper) "-" else paperOnlyClasses.joinToString(","), api.asPath)
        }
    }
    linkageCheck { dependsOn(task) }
}

// The shipped JARs: expected contents only, the embedded helper identical to the standalone one,
// every class verified by the JVM and linked against each API, entry points present.
val releaseCheck by tasks.registering(Exec::class) {
    group = "verification"
    dependsOn(release)
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    val helperJar = restoreJar.flatMap { it.archiveFile }
    val spigotClasspath: FileCollection = spigotCurrent
    val paperClasspath: FileCollection = paperCurrent
    val script = file("tools/check_release.py")
    inputs.files(pluginJar, helperJar, spigotClasspath, paperClasspath, script)
    val javaHome = System.getProperty("java.home")
    val projectVersion = project.version.toString()
    doFirst {
        commandLine("python3", script.absolutePath,
            "--java-home", javaHome,
            "--version", projectVersion,
            "--plugin", pluginJar.get().asFile.absolutePath,
            "--helper", helperJar.get().asFile.absolutePath,
            "--spigot-classpath", spigotClasspath.asPath,
            "--paper-classpath", paperClasspath.asPath)
    }
}

tasks.check {
    dependsOn(compileAgainstCurrentSpigot, compileAgainstCurrentPaper, paperDeprecationCheck, linkageCheck, releaseCheck)
}

tasks.register("dependencyInventory") {
    val views = listOf(configurations.compileClasspath.get(), configurations["paperCompileClasspath"], spigotCurrent, paperCurrent)
    val target = layout.buildDirectory.file("dependency-inventory.json")
    doLast {
        val rows = sortedMapOf<String, Map<String, String>>()
        views.forEach { view ->
            view.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val id = artifact.moduleVersion.id
                rows["$id:${artifact.file.name}"] = mapOf("group" to id.group, "name" to id.name, "version" to id.version, "file" to artifact.file.name)
            }
        }
        target.get().asFile.apply { parentFile.mkdirs() }.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(rows.values)))
    }
}
