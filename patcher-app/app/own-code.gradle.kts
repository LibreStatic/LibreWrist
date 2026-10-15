// Our own code as app assets, in placeholder form (package `shminterop`; the engine applies the real
// prefix on the phone). Each task mirrors the shell build exactly: same platform jar, build-tools,
// javac / d8 / aapt2 flags (watch-mod/build-watch-mod.sh, scripts/lib/common.sh java_to_smali,
// shm-bridge/scripts/build-bridge.sh), minus prefix staging and signing.
//
// build.gradle.kts passes the AGP SDK directory in and registers the assets task for every variant.
//
// Output (merged into every variant's assets):
//   owncode/injections/{watch-mod,alarm-fork}.dex    one classes.dex each
//   owncode/tools/keep-awake.dex                     helper the patcher runs on the watch during installs
//   owncode/apps/shm-bridge-{watch,phone}.apk      unsigned (the catalogue `ownApp` path with "/" as "-")
//   catalogue/{catalogue.json,catalogue.json.sig,catalogue.pub}
//
// Configuration comes from the environment, like config.env: ANDROID_HOME (default: the SDK AGP uses),
// BUILD_TOOLS_VERSION (36.0.0), PLATFORM_API (36). The app's own compileSdk is not used here.

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

// Set by build.gradle.kts (AGP types are not visible to script plugins): the SDK directory AGP uses.
@Suppress("UNCHECKED_CAST")
val agpSdkDir = extra["ownCode.sdkDirectory"] as Provider<String>
val repoRoot: File = rootProject.projectDir.parentFile
val btVersion = providers.environmentVariable("BUILD_TOOLS_VERSION").orElse("36.0.0")
val apiLevel = providers.environmentVariable("PLATFORM_API").orElse("36")
val sdkDir = providers.environmentVariable("ANDROID_HOME")
    .orElse(agpSdkDir)
val btDir = sdkDir.zip(btVersion) { sdk, v -> File(sdk, "build-tools/$v") }
val pjFile = sdkDir.zip(apiLevel) { sdk, api -> File(sdk, "platforms/android-$api/android.jar") }

/** Builds a dex (kind = dex) or an unsigned APK with classes.dex (kind = apk) from a Java source tree. */
@CacheableTask
abstract class BuildOwnCode @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: DirectoryProperty
    /** Signature-only stubs on the javac classpath that stay out of the dex (watch-mod). */
    @get:InputDirectory @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val stubs: DirectoryProperty
    @get:InputDirectory @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val resources: DirectoryProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifest: RegularFileProperty
    @get:Input abstract val javaRelease: Property<Int>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input @get:Optional abstract val targetSdk: Property<Int>
    /** Extra javac flag `-encoding UTF-8` (bridge apps only, as in build-bridge.sh). */
    @get:Input abstract val utf8: Property<Boolean>
    /** Only this top-level class directory goes into the jar given to d8 (empty = everything). */
    @get:Input abstract val jarOnly: Property<String>
    @get:Input abstract val buildToolsVersion: Property<String>
    @get:Input abstract val platformApi: Property<String>
    @get:Input abstract val javaVersion: Property<String>
    @get:Internal abstract val buildToolsDir: Property<File>
    @get:Internal abstract val platformJar: Property<File>
    @get:Input abstract val asApk: Property<Boolean>
    // The tools themselves are inputs (not validated here: doFirst reports a missing SDK clearly).
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val toolFiles: ConfigurableFileCollection
    @get:OutputFile abstract val output: RegularFileProperty

    private fun run(vararg cmd: String) {
        val log = ByteArrayOutputStream()
        val r = exec.exec { commandLine(*cmd); standardOutput = log; errorOutput = log; isIgnoreExitValue = true }
        if (r.exitValue != 0) throw GradleException("${cmd.first()} failed (exit ${r.exitValue}):\n$log")
    }

    private fun javaTool(name: String) = File(System.getProperty("java.home"), "bin/$name").path

    @TaskAction
    fun build() {
        val jar = platformJar.get().path
        val bt = buildToolsDir.get()
        val missing = listOf(platformJar.get(), File(bt, "d8"), File(bt, "aapt2")).filter { !it.isFile }
        if (missing.isNotEmpty()) throw GradleException(
            "Missing Android SDK pieces for the own-code assets: ${missing.joinToString()} " +
                "(set ANDROID_HOME, BUILD_TOOLS_VERSION=${buildToolsVersion.get()}, PLATFORM_API=${platformApi.get()})")
        val work = temporaryDir.resolve("w").also { it.deleteRecursively(); it.mkdirs() }
        val classes = work.resolve("classes").also { it.mkdirs() }
        val flags = mutableListOf("--release", javaRelease.get().toString(), "-nowarn")
        if (utf8.get()) flags += listOf("-encoding", "UTF-8")

        var classpath = jar
        stubs.orNull?.asFile?.let { stubDir ->
            val stubClasses = work.resolve("stub-classes").also { it.mkdirs() }
            run(javaTool("javac"), "--release", javaRelease.get().toString(), "-nowarn", "-classpath", jar,
                "-d", stubClasses.path, *javaFiles(stubDir))
            classpath = "$jar:${stubClasses.path}"
        }
        run(javaTool("javac"), *flags.toTypedArray(), "-classpath", classpath, "-d", classes.path,
            *javaFiles(sources.get().asFile))
        if (classes.walk().none { it.extension == "class" }) throw GradleException("javac produced no classes")

        val classesJar = work.resolve("classes.jar")
        run(javaTool("jar"), "cf", classesJar.path, "-C", classes.path, jarOnly.get().ifEmpty { "." })
        val dexDir = work.resolve("dex").also { it.mkdirs() }
        val d8Args = mutableListOf(File(bt, "d8").path, "--release")
        if (asApk.get()) d8Args += listOf("--lib", jar, "--min-api", minSdk.get().toString())
        else d8Args += listOf("--min-api", minSdk.get().toString(), "--lib", jar)
        stubs.orNull?.let { d8Args += listOf("--classpath", work.resolve("stub-classes").path) }
        run(*d8Args.toTypedArray(), "--output", dexDir.path, classesJar.path)
        val dex = dexDir.resolve("classes.dex")
        val out = output.get().asFile.also { it.parentFile.mkdirs() }
        if (!asApk.get()) {
            val dexes = dexDir.listFiles()!!.filter { it.extension == "dex" }
            if (dexes.size != 1) throw GradleException("expected exactly one classes.dex, got $dexes")
            dex.copyTo(out, overwrite = true)
            return
        }

        val base = work.resolve("base.apk")
        val aapt2Args = mutableListOf<String>()
        resources.orNull?.asFile?.let { res ->
            val resZip = work.resolve("res.zip")
            run(File(bt, "aapt2").path, "compile", "--dir", res.path, "-o", resZip.path)
            aapt2Args += resZip.path
        }
        run(File(bt, "aapt2").path, "link", "-o", base.path, "-I", jar,
            "--manifest", manifest.get().asFile.path, *aapt2Args.toTypedArray(),
            "--min-sdk-version", minSdk.get().toString(), "--target-sdk-version", targetSdk.get().toString())
        // Same as the script: append classes.dex (deflated) to the aapt2 output.
        ZipFile(base).use { zin ->
            ZipOutputStream(out.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    val ne = ZipEntry(e.name).apply { method = e.method; time = e.time
                        if (e.method == ZipEntry.STORED) { size = e.size; compressedSize = e.compressedSize; crc = e.crc } }
                    zout.putNextEntry(ne); zin.getInputStream(e).copyTo(zout); zout.closeEntry()
                }
                zout.putNextEntry(ZipEntry("classes.dex")); dex.inputStream().copyTo(zout); zout.closeEntry()
            }
        }
    }

    private fun javaFiles(dir: File): Array<String> =
        dir.walkTopDown().filter { it.isFile && it.extension == "java" }.map { it.path }.sorted().toList().toTypedArray()
}

val stagingDir = layout.buildDirectory.dir("intermediates/ownCode")

fun registerBuild(name: String, out: String, apk: Boolean, cfg: BuildOwnCode.() -> Unit) =
    tasks.register<BuildOwnCode>(name) {
        group = "build"
        asApk.set(apk)
        buildToolsVersion.set(btVersion)
        platformApi.set(apiLevel)
        javaVersion.set(System.getProperty("java.version"))
        utf8.set(false)
        jarOnly.set("")
        buildToolsDir.set(btDir)
        platformJar.set(pjFile)
        toolFiles.from(btDir.map { File(it, "d8") }, btDir.map { File(it, "aapt2") }, pjFile)
        output.set(stagingDir.map { it.file(out) })
        cfg()
    }

val watchMod = registerBuild("buildWatchModDex", "owncode/injections/watch-mod.dex", false) {
    sources.set(repoRoot.resolve("watch-mod/src"))
    stubs.set(repoRoot.resolve("watch-mod/stubs"))
    javaRelease.set(8); minSdk.set(26); jarOnly.set("shminterop")
}
val alarmFork = registerBuild("buildAlarmForkDex", "owncode/injections/alarm-fork.dex", false) {
    sources.set(repoRoot.resolve("shm-bridge/alarm-fork/src"))
    javaRelease.set(11); minSdk.set(34)
}
fun bridge(dir: String, minSdk: Int, targetSdk: Int) =
    registerBuild("buildBridge${dir.replaceFirstChar { it.uppercase() }}Apk", "owncode/apps/shm-bridge-$dir.apk", true) {
        val root = repoRoot.resolve("shm-bridge/$dir")
        sources.set(root.resolve("src"))
        if (root.resolve("res").isDirectory) resources.set(root.resolve("res"))
        manifest.set(root.resolve("AndroidManifest.xml"))
        javaRelease.set(11); utf8.set(true)
        this.minSdk.set(minSdk); this.targetSdk.set(targetSdk)
    }
val keepAwake = registerBuild("buildKeepAwakeDex", "owncode/tools/keep-awake.dex", false) {
    sources.set(repoRoot.resolve("patcher-app/keep-awake/src"))
    javaRelease.set(8); minSdk.set(30)
}
val bridgeWatch = bridge("watch", 30, 36)
val bridgePhone = bridge("phone", 29, 35)

/** Merges the built files and the signed catalogue into one directory registered as generated assets. */
@CacheableTask
abstract class AssembleOwnCodeAssets @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val built: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val catalogue: ConfigurableFileCollection
    @get:OutputDirectory abstract val assets: DirectoryProperty

    @TaskAction
    fun assemble() {
        fs.sync {
            into(assets)
            from(built) { include("owncode/**") }
            from(catalogue) { into("catalogue") }
        }
    }
}

val ownCodeAssets = tasks.register<AssembleOwnCodeAssets>("ownCodeAssets") {
    group = "build"
    description = "Builds the placeholder-form own-code assets and copies the signed catalogue."
    dependsOn(watchMod, alarmFork, keepAwake, bridgeWatch, bridgePhone)
    built.set(stagingDir)
    catalogue.from(
        repoRoot.resolve("catalogue/catalogue.json"), repoRoot.resolve("catalogue/catalogue.json.sig"),
        repoRoot.resolve("catalogue/catalogue.pub"),
    )
    assets.set(layout.buildDirectory.dir("generated/ownCodeAssets"))
}

// build.gradle.kts registers this task as a generated assets source of every variant.
extra["ownCode.assetsTask"] = ownCodeAssets
