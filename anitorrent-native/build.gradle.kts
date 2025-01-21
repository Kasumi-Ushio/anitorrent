/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    idea
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

val archs = buildList {
    val abis = getPropertyOrNull("ani.android.abis")?.trim()
    if (!abis.isNullOrEmpty()) {
        addAll(abis.split(",").map { it.trim() })
    } else {
        add("arm64-v8a")
        add("armeabi-v7a")
        add("x86_64")
        add("x86")
    }
}

kotlin {
    jvmToolchain(8)
    jvm("desktop")
    androidTarget()

    applyDefaultHierarchyTemplate {
        common {
            group("jvm") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
//        androidMain {
//            kotlin.srcDirs(listOf("gen/java"))
//        }
        getByName("jvmMain") {
            dependencies {
                api(projects.anitorrentNativeDesktopJni)
            }
        }
    }
}

//kotlin.sourceSets.getByName("jvmMain") {
//    java.setSrcDirs(listOf("gen/java"))
//}

android {
    namespace = "org.openani.anitorrent.natives"
    compileSdk = getIntProperty("android.compile.sdk")
    defaultConfig {
        minSdk = getIntProperty("android.min.sdk")
        testOptions.targetSdk = getIntProperty("android.compile.sdk")
        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your app.
            abiFilters.clear()
            //noinspection ChromeOsAbiSupport
            abiFilters += archs
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include(*archs.toTypedArray())
            isUniversalApk = true // 额外构建一个
        }
    }
    signingConfigs {
        kotlin.runCatching { getProperty("signing_release_storeFileFromRoot") }.getOrNull()?.let {
            create("release") {
                storeFile = rootProject.file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
        kotlin.runCatching { getProperty("signing_release_storeFile") }.getOrNull()?.let {
            create("release") {
                storeFile = file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
    }
    packaging {
        resources {
            merges.add("META-INF/DEPENDENCIES") // log4j
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                *sharedAndroidProguardRules(),
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = projectDir.resolve("CMakeLists.txt")
        } 
    }
}

/// ANITORRENT

val anitorrentRootDir = projectDir
val anitorrentBuildDir = anitorrentRootDir.resolve("build-ci")

val generateSwigImpl = tasks.register("generateSwigImpl", Exec::class.java) {
    group = "anitorrent"

    val swig = getLocalProperty("SWIG") ?: "swig"
//    swig -java -c++ \
//    -o ./src/anitorrent_wrap.cpp \
//    -outdir ./java/me/him188/ani/app/torrent/anitorrent/binding \
//    -package me.him188.ani.app.torrent.anitorrent.binding \
//            ./anitorrent.i

    val swigI = anitorrentRootDir.resolve("anitorrent.i")
    inputs.file(swigI)
    inputs.dir(anitorrentRootDir.resolve("cpp/include"))
    outputs.file(anitorrentRootDir.resolve("gen/cpp/anitorrent_wrap.cpp"))
    outputs.dir(anitorrentRootDir.resolve("gen/java"))

    val cppDir = anitorrentRootDir.resolve("gen/cpp")
    val javaDir = anitorrentRootDir.resolve("gen/java/org/openani/anitorrent/binding/")
    commandLine = listOf(
        swig,
        "-java", "-c++", "-directors", "-cppext", "cpp", "-addextern",
        "-o", cppDir.resolve("anitorrent_wrap.cpp").absolutePath,
        "-outdir", javaDir.absolutePath,
        "-package", "org.openani.anitorrent.binding",
        swigI.absolutePath,
    )
    doFirst {
        cppDir.mkdirs()
        javaDir.mkdirs()
    }
}

val patchGeneratedSwig = tasks.register("patchGeneratedSwig") {
    group = "anitorrent"
    dependsOn(generateSwigImpl)
    val gen = file("gen")
    inputs.dir(gen)
    outputs.dir(gen)
    doLast {
        gen.walk().forEach {
            if (it.extension in listOf("cpp", "h", "java")) {
                if (!it.readText().contains("@formatter"))
                    it.writeText("//@formatter:off\n" + it.readText() +
                            "\n// @formatter:on",
                    )
            }
        }
    }
}

val generateSwig = tasks.register("generateSwig") {
    group = "anitorrent"
    dependsOn(patchGeneratedSwig)
}

val configureAnitorrent = tasks.register("configureAnitorrent", Exec::class.java) {
    group = "anitorrent"
    // /Users/him188/Applications/CLion.app/Contents/bin/cmake/mac/aarch64/bin/cmake -DCMAKE_BUILD_TYPE=Debug 
    // -DCMAKE_MAKE_PROGRAM=/Users/him188/Applications/CLion.app/Contents/bin/ninja/mac/aarch64/ninja 
    // -G Ninja -S /Users/him188/Projects/ani/torrent/anitorrent 
    // -B /Users/him188/Projects/ani/torrent/anitorrent/cmake-build-debug

    val cmake = getPropertyOrNull("CMAKE") ?: "cmake"
    val ninja = getPropertyOrNull("NINJA") ?: "ninja"

    // Prefer clang, as the CI is tested with Clang
    val compilerC = getPropertyOrNull("CMAKE_C_COMPILER") ?: kotlin.run {
        when (getOs()) {
            Os.Windows -> {
                null
            }

            Os.Unknown,
            Os.MacOS,
            Os.Linux -> {
                File("/usr/bin/clang").takeIf { it.exists() }
                    ?: File("/usr/bin/gcc").takeIf { it.exists() }
            }
        }?.absolutePath?.also {
            logger.info("Using C compiler: $it")
        }
    }
    val compilerCxx = getPropertyOrNull("CMAKE_CXX_COMPILER") ?: kotlin.run {
        when (getOs()) {
            Os.Windows -> {
                File("C:/Program Files/LLVM/bin/clang++.exe").takeIf { it.exists() }
                    ?: File("C:/Program Files/LLVM/bin/clang++.exe").takeIf { it.exists() }
            }

            Os.Unknown,
            Os.MacOS,
            Os.Linux -> {
                File("/usr/bin/clang++").takeIf { it.exists() }
                    ?: File("/usr/bin/g++").takeIf { it.exists() }
            }
        }?.absolutePath?.also {
            logger.info("Using CXX compiler: $it")
        }
    }
    val isWindows = getOs() == Os.Windows

    inputs.file(anitorrentRootDir.resolve("CMakeLists.txt"))
    outputs.dir(anitorrentBuildDir)

    fun String.sanitize(): String {
        return this.replace("\\", "/").trim()
    }

    val buildType = getPropertyOrNull("CMAKE_BUILD_TYPE") ?: "Debug"
    check(buildType == "Debug" || buildType == "Release" || buildType == "RelWithDebInfo" || buildType == "MinSizeRel") {
        "Invalid build type: '$buildType'. Supported: Debug, Release, RelWithDebInfo, MinSizeRel"
    }

    // Note: to build in release mode on Windows:
    // --config Release
    // See also https://github.com/arvidn/libtorrent/issues/5111#issuecomment-688540049
    commandLine = buildList {
        add(cmake)
        add("-DCMAKE_BUILD_TYPE=$buildType")
        add("-DCMAKE_C_FLAGS_RELEASE=-O3")
        if (isWindows) {
            add("-Dencryption=OFF")
        }
        getPropertyOrNull("Boost_INCLUDE_DIR")?.let { add("-DBoost_INCLUDE_DIR=${it.sanitize()}") }
        if (!isWindows) {
            compilerC?.let { add("-DCMAKE_C_COMPILER=${compilerC.sanitize()}") }
            compilerCxx?.let { add("-DCMAKE_CXX_COMPILER=${compilerCxx.sanitize()}") }
            add("-DCMAKE_MAKE_PROGRAM=${ninja.sanitize()}")
            add("-G")
            add("Ninja")
        } else {
            getPropertyOrNull("CMAKE_TOOLCHAIN_FILE")?.let { add("-DCMAKE_TOOLCHAIN_FILE=${it.sanitize()}") }
            if (getPropertyOrNull("USE_NINJA")?.toBooleanStrict() == true) {
                add("-DCMAKE_MAKE_PROGRAM=${ninja.sanitize()}")
                add("-G")
                add("Ninja")
            }
        }
        add("-S")
        add(anitorrentRootDir.absolutePath)
        add("-B")
        add(anitorrentBuildDir.absolutePath)
    }
    logger.warn(commandLine.joinToString(" "))
}


val buildAnitorrent = tasks.register("buildAnitorrent", Exec::class.java) {
    group = "anitorrent"
    dependsOn(configureAnitorrent)
    mustRunAfter(generateSwigImpl)

    val cmake = getPropertyOrNull("CMAKE") ?: "cmake"
    val isWindows = getOs() == Os.Windows
    val buildType = getPropertyOrNull("CMAKE_BUILD_TYPE") ?: "Debug"

    inputs.file(anitorrentRootDir.resolve("CMakeLists.txt"))
    inputs.dir(anitorrentRootDir.resolve("cpp/include"))
    inputs.dir(anitorrentRootDir.resolve("cpp/src"))
    inputs.file(anitorrentRootDir.resolve("gen/cpp/anitorrent_wrap.cpp"))
    outputs.dir(anitorrentBuildDir)

    // /Users/him188/Applications/CLion.app/Contents/bin/cmake/mac/aarch64/bin/cmake 
    // --build /Users/him188/Projects/ani/torrent/anitorrent/cmake-build-debug --target anitorrent -j 10
    commandLine = listOf(
        cmake,
        "--build", anitorrentBuildDir.absolutePath,
        "--target", "anitorrent",
        *if (isWindows && buildType == "Release") arrayOf("--config", "Release") else emptyArray(),
        "-j", Runtime.getRuntime().availableProcessors().toString(),
    )
}

val copyNativeFiles by tasks.registering {
    dependsOn(buildAnitorrent)
    group = "anitorrent"
    description = "Copy anitrorent native files and dependencies to build/native-files"

    val cmakeCache = anitorrentBuildDir.resolve("CMakeCache.txt")
    if (cmakeCache.exists()) {
        inputs.file(cmakeCache)
    }

    val targetDir = layout.buildDirectory.dir("native-files")
    outputs.dir(targetDir)

    val buildType = getPropertyOrNull("CMAKE_BUILD_TYPE") ?: "Debug"
    inputs.property("buildType", buildType)

    val anitorrentBuildDir = anitorrentBuildDir

    val os = getOs()
    inputs.property("os", os)
    doLast {
        class Dep(
            val path: File,
            val overrideName: String?
        )

        fun getAnitorrentNativeFiles(): List<Dep> {
            return buildList {
                fun add(file: File) {
                    add(Dep(file, null))
                }

                fun addIfExist(file: File) {
                    if (file.exists()) {
                        add(file)
                    }
                }

                when (getOs()) {
                    Os.Windows -> {
                        add(anitorrentBuildDir.resolve("$buildType/anitorrent.dll"))
                        add(anitorrentBuildDir.resolve("_deps/libtorrent-build/$buildType/torrent-rasterbar.dll"))
//                        addIfExist(anitorrentBuildDir.resolve("_deps/libtorrent-build/$buildType/libssl-3-x64.dll"))
//                        addIfExist(anitorrentBuildDir.resolve("_deps/libtorrent-build/$buildType/libcrypto-3-x64.dll"))
                    }

                    Os.MacOS -> {
                        add(anitorrentBuildDir.resolve("libanitorrent.dylib"))
                    }

                    Os.Unknown, Os.Linux -> {
                        add(anitorrentBuildDir.resolve("libanitorrent.so"))
                        add(anitorrentBuildDir.resolve("_deps/libtorrent-build/libtorrent-rasterbar.2.0.10.so"))
                    }
                }
            }
        }

        fun parseCMakeCache(cmakeCache: File): Map<String, String> {
            return cmakeCache.readText().lines().filterNot { it.startsWith("#") }.mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                parts[0].trim() to parts[1].trim()
            }.toMap()
        }

        val map = parseCMakeCache(cmakeCache)

        fun Map<String, String>.getOrFail(key: String): String {
            return this[key] ?: error("Key $key not found in CMakeCache")
        }

        val dependencies = buildMap {
            if (os != Os.MacOS) { // macos uses static linking
                map["OPENSSL_CRYPTO_LIBRARY:FILEPATH"]?.let {
                    put("OPENSSL_CRYPTO_LIBRARY", File(it))
                }
                map["OPENSSL_SSL_LIBRARY:FILEPATH"]?.let {
                    put("OPENSSL_SSL_LIBRARY", File(it))
                }
            }

            if (os == Os.Windows) {
                // LIB_EAY_RELEASE:FILEPATH=C:/vcpkg/installed/x64-windows/lib/libcrypto.lib
                // SSL_EAY_RELEASE:FILEPATH=C:/vcpkg/installed/x64-windows/lib/libssl.lib
                fun findDll(libFile: File): List<File> {
                    val matched = libFile.parentFile.parentFile.resolve("bin")
                        .listFiles().orEmpty()
                        .filter { it.extension == "dll" && it.nameWithoutExtension.startsWith(libFile.nameWithoutExtension) }
                    return matched
                }

                fun findSystemDll(filename: String): File? {
                    val systemDir = File("C:/Windows/System32")
                    val systemDll = systemDir.resolve(filename)
                    if (systemDll.exists()) {
                        return systemDll
                    }
                    return null
                }
                findSystemDll("vcruntime140.dll")?.let {
                    put("vcruntime140", it)
                }
                findSystemDll("vcruntime140_1.dll")?.let {
                    put("vcruntime140_1", it)
                }
                findSystemDll("msvcp140.dll")?.let {
                    put("MSVCP140", it)
                }
                map["LIB_EAY_RELEASE:FILEPATH"]?.let {
                    findDll(File(it)).forEachIndexed { index, file ->
                        put("LIB_EAY_RELEASE_${index}", file)
                    }
                }
                map["SSL_EAY_RELEASE:FILEPATH"]?.let {
                    findDll(File(it)).forEachIndexed { index, file ->
                        put("SSL_EAY_RELEASE_${index}", file)
                    }
                }
            }
        }

        (dependencies.values.map { Dep(it, null) } + getAnitorrentNativeFiles()).forEach {
            val target = targetDir.get().file(it.overrideName ?: it.path.name)
            it.path.copyTo(target.asFile, overwrite = true)
        }
    }
}

tasks.withType(KotlinJvmCompile::class) {
    dependsOn(copyNativeFiles)
    mustRunAfter(generateSwigImpl)
}

val supportedOsTriples = listOf("macos-aarch64", "macos-x64", "windows-x64")

val nativeJarsDir = layout.buildDirectory.dir("native-jars")
val nativeJarForCurrentPlatform = tasks.register("nativeJarForCurrentPlatform", Jar::class.java) {
    dependsOn(copyNativeFiles)
    description =
        "Create a jar for the native files for current platform, saving it to build/libs/anitorrent-native-0.1.0-macos-arm64.jar"
    group = "anitorrent"
    archiveClassifier.set(getOsTriple())
    from(copyNativeFiles.map { it.outputs.files.singleFile.listFiles().orEmpty() })
}
val copyNativeJarForCurrentPlatform = tasks.register("copyNativeJarForCurrentPlatform", Copy::class.java) {
    dependsOn(nativeJarForCurrentPlatform)
    description =
        "Copy native jar for current platform, saving it to build/native-jars/anitorrent-native-0.1.0-macos-arm64.jar"
    group = "anitorrent"
    from(nativeJarForCurrentPlatform.flatMap { it.archiveFile })
    into(nativeJarsDir)
}

tasks.named("assemble") {
    dependsOn(copyNativeJarForCurrentPlatform)
}

idea {
    module {
        excludeDirs.add(anitorrentBuildDir)
        excludeDirs.add(file("cmake-build-debug"))
        excludeDirs.add(file("cmake-build-release"))
    }
}

description = "Anitorrent Native"

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true, androidVariantsToPublish = listOf("release", "debug")))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    configurePom(project)
}

tasks.matching { it.name.startsWith("publishDesktopPublicationTo") }.all {
    dependsOn(copyNativeJarForCurrentPlatform)
}

tasks.getByName("signDesktopPublication") {
    dependsOn(copyNativeJarForCurrentPlatform)
}

afterEvaluate {
    publishing {
        publications {
            getByName("desktop", MavenPublication::class) {
                val platforms = if (getLocalProperty("ani.publishing.onlyHostOS") == "true") {
                    listOf("macos-aarch64")
                } else {
                    supportedOsTriples
                }
                platforms.forEach { platform ->
                    artifact(nativeJarsDir.map { it.file("${project.name}-${project.version}-$platform.jar") }) {
                        classifier = platform
                    }
                }
            }
        }
    }
}
