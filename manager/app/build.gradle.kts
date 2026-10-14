@file:Suppress("UnstableApiUsage")

import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.protobuf)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion = rootProject.extra["androidCompileSdkVersion"] as Int
val androidCompileSdkVersionMinor = rootProject.extra["androidCompileSdkVersionMinor"] as Int
val androidCompileNdkVersion = rootProject.extra["androidCompileNdkVersion"] as String
val androidBuildToolsVersion = rootProject.extra["androidBuildToolsVersion"] as String
val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion = rootProject.extra["androidTargetSdkVersion"] as Int
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion
val managerVersionCode = rootProject.extra["managerVersionCode"] as Int
val managerVersionName = rootProject.extra["managerVersionName"] as String
val kernelSourceId = rootProject.extra["kernelSourceId"] as String
val camdSourceId = rootProject.extra["camdSourceId"] as String

val isPrBuild = project.findProperty("IS_PR_BUILD")?.toString()?.toBoolean() ?: false
val defaultManagerPackageName = if (isPrBuild) "cam.su.kernel.pr" else "cam.su.kernel"
val defaultManagerName = if (isPrBuild) "SU Kernel PR" else "SU Kernel"
val managerPackageName = project.findProperty("KSU_PACKAGE_NAME")?.toString() ?: defaultManagerPackageName
val managerName = project.findProperty("KSU_NAME")?.toString() ?: defaultManagerName

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        ofNonTest().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

val baseCFlags = listOf(
    "-Wall", "-Qunused-arguments", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
    "-fno-exceptions", "-fno-stack-protector", "-fomit-frame-pointer",
    "-Wno-builtin-macro-redefined", "-Wno-unused-value", "-D__FILE__=__FILE_NAME__"
)
val baseCppFlags = baseCFlags + "-fno-rtti"

android {
    namespace = "cam.su.kernel"

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_CXX_FLAGS_DEBUG=-Og", "-DCMAKE_C_FLAGS_DEBUG=-Og")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            externalNativeBuild {
                cmake {
                    arguments += "-DDEBUG_SYMBOLS_PATH=${layout.buildDirectory.get().asFile.absolutePath}/symbols"
                    arguments += "-DCMAKE_BUILD_TYPE=Release"

                    val releaseFlags = listOf(
                        "-flto", "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
                        "-fno-unwind-tables", "-fno-asynchronous-unwind-tables", "-Wl,--exclude-libs,ALL"
                    )
                    val configFlags = listOf("-Oz", "-DNDEBUG").joinToString(" ")

                    cppFlags += releaseFlags
                    cFlags += releaseFlags

                    arguments += listOf(
                        "-DCMAKE_CXX_FLAGS_RELEASE=$configFlags",
                        "-DCMAKE_C_FLAGS_RELEASE=$configFlags",
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--icf=all -s -Wl,--hash-style=sysv -Wl,-z,norelro"
                    )
                }
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        resValues = true
        compose = true
        prefab = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
        // Only English and Vietnamese are kept up to date (Cam's own strings, "SU Kernel" naming).
        // The other values-* folders stay as upstream ships them so merges do not conflict, but are
        // left out of the APK: those users get English instead of half-translated KernelSU text.
        localeFilters += listOf("en", "vi")
    }
    compileSdk {
        version =
            release(androidCompileSdkVersion) {
                minorApiLevel = androidCompileSdkVersionMinor
            }
    }
    buildToolsVersion = androidBuildToolsVersion
    ndkVersion = androidCompileNdkVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName
        applicationId = managerPackageName

        buildConfigField("boolean", "IS_PR_BUILD", isPrBuild.toString())
        buildConfigField("String", "KERNEL_SOURCE_ID", "\"$kernelSourceId\"")
        resValue("string", "app_name", managerName)

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                cFlags += baseCFlags + "-std=c2x"
                cppFlags += baseCppFlags + "-std=c++2b"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "riscv64")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.addAll(listOf("META-INF/**", "kotlin/**", "**.bin"))
    }
}

// CHANGELOG.md (repo root) is packed for the "What's new" dialog (ChangelogParser.ASSET_PATH).
abstract class CopyChangelog : DefaultTask() {
    @get:InputFile
    abstract val changelog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("changelog")
        dir.deleteRecursively()
        dir.mkdirs()
        changelog.get().asFile.copyTo(dir.resolve("CHANGELOG.md"), overwrite = true)
    }
}

val copyChangelog = tasks.register<CopyChangelog>("copyChangelog") {
    changelog.set(rootProject.file("../CHANGELOG.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyChangelog, CopyChangelog::outputDir)
    }
}

/**
 * Gradle packs whatever libcamd.so sits in jniLibs, and the Manager copies it over /data/adb/camd at
 * every launch, so a camd built from older sources would quietly replace the newer daemon. camd
 * carries `CAMD_SOURCE_ID=<id>;` (userspace/camd/build.rs); this compares it with the committed
 * sources. A release build stops on a mismatch unless `-PallowStaleCamd`; a debug build only warns
 * (packing the device's own camd for a quick test is fine). No libcamd.so at all is left alone:
 * CI builds the APK without it and adds camd afterwards.
 */
abstract class CheckCamdSource : DefaultTask() {
    @get:Input
    abstract val expectedId: Property<String>

    @get:Input
    abstract val strict: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraries: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val expected = expectedId.get()
        if (expected.isEmpty()) {
            logger.warn("camd source check skipped: no git sources")
            return
        }
        val marker = "CAMD_SOURCE_ID=".toByteArray()
        val stale = libraries.files.filter { it.isFile }.mapNotNull { lib ->
            val bytes = lib.readBytes()
            val at = indexOf(bytes, marker)
            val found = if (at < 0) "none (built before the id existed)" else {
                val start = at + marker.size
                val end = (start until bytes.size).firstOrNull { bytes[it] == ';'.code.toByte() } ?: start
                String(bytes, start, end - start)
            }
            if (found == expected) null else "${lib.parentFile.name}/${lib.name}: $found"
        }
        if (stale.isEmpty()) return
        val message = "libcamd.so was built from other sources than this checkout (expected $expected):\n" +
            stale.joinToString("\n") { "  $it" } +
            "\nBuild camd again and copy it into app/src/main/jniLibs, or pass -PallowStaleCamd to pack it anyway."
        if (strict.get()) throw GradleException(message) else logger.warn("warning: $message")
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

val allowStaleCamd = project.findProperty("allowStaleCamd") != null

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val check = tasks.register<CheckCamdSource>("check${name}CamdSource") {
            expectedId.set(camdSourceId)
            strict.set(variant.buildType == "release" && !allowStaleCamd)
            libraries.from(fileTree("src/main/jniLibs") { include("*/libcamd.so") })
        }
        tasks.matching { it.name == "pre${name}Build" }.configureEach { dependsOn(check) }
    }
}

// Unit tests read the shipped assets and CHANGELOG.md straight from disk (HidingRulesRepositoryTest,
// ChangelogParserTest); declare them so editing either re-runs the tests instead of "up-to-date".
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("../CHANGELOG.md")).withPathSensitivity(PathSensitivity.NONE)
}

base {
    archivesName.set(
        "${managerName.replace(" ", "_")}_${managerVersionName}_${managerVersionCode}"
    )
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.filament.android)
    implementation(libs.filament.gltfio)
    implementation(libs.filament.utils)
    testImplementation(libs.junit)
    // the android.jar org.json is a stub in local unit tests
    testImplementation(libs.json.org)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.zxing.core)
    implementation(libs.androidx.navigationevent.compose)

    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)
    implementation(libs.com.github.topjohnwu.libsu.io)

    implementation(libs.dev.rikka.rikkax.parcelablelist)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    implementation(libs.androidx.webkit)

    implementation(libs.lsposed.cxx)

    implementation(libs.hiddenapibypass)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.material.kolor)

    implementation(libs.appiconloader)

    implementation(libs.commons.compress)
    implementation(libs.xz)
    // Files tab terminal: a real PTY and ANSI rendering (Apache 2.0, Termux)
    implementation(libs.termux.terminal.view)
    implementation(libs.protobuf.kotlin.lite)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}
