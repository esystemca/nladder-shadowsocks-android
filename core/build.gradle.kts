import com.android.build.gradle.internal.tasks.factory.dependsOn

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("org.mozilla.rust-android-gradle.rust-android")
    kotlin("android")
    id("kotlin-parcelize")
}

setupCore()

val allAbis = mapOf("arm" to "armeabi-v7a", "arm64" to "arm64-v8a", "x86" to "x86", "x86_64" to "x86_64")
val targetAbi = findProperty("TARGET_ABI")?.toString()

android {
    namespace = "com.github.shadowsocks.core"

    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")

        externalNativeBuild.ndkBuild {
            val abis = if (targetAbi != null) listOf(allAbis.getValue(targetAbi)) else allAbis.values.toList()
            abiFilters(*abis.toTypedArray())
            arguments("-j${Runtime.getRuntime().availableProcessors()}")
        }

        ksp {
            arg("room.incremental", "true")
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    externalNativeBuild.ndkBuild.path("src/main/jni/Android.mk")

    sourceSets.getByName("androidTest") {
        assets.setSrcDirs(assets.srcDirs + files("$projectDir/schemas"))
    }

    buildFeatures.aidl = true
}

cargo {
    findProperty("RUSTC_COMMAND")?.toString()?.let { rustcCommand = it } ?: run {
        val userCargoRustc = file("${System.getProperty("user.home")}/.cargo/bin/rustc${if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""}")
        if (userCargoRustc.exists()) rustcCommand = userCargoRustc.absolutePath
    }
    findProperty("CARGO_COMMAND")?.toString()?.let { cargoCommand = it } ?: run {
        val userCargoCargo = file("${System.getProperty("user.home")}/.cargo/bin/cargo${if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""}")
        if (userCargoCargo.exists()) cargoCommand = userCargoCargo.absolutePath
    }

    module = "src/main/rust/shadowsocks-rust"
    libname = "sslocal"
    targets = if (targetAbi != null) listOf(targetAbi) else listOf("arm", "arm64", "x86", "x86_64")
    profile = findProperty("CARGO_PROFILE")?.toString() ?: currentFlavor
    extraCargoBuildArguments = listOf("--bin", libname!!)
    featureSpec.noDefaultBut(arrayOf(
        "stream-cipher",
        "aead-cipher-extra",
        "logging",
        "local-flow-stat",
        "local-dns",
        "aead-cipher-2022",
    ))
    exec = { spec, toolchain ->
        run {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("python3", "-V"))
                if (proc.waitFor() != 0) throw Exception("python3 failed")
                spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", "python3")
                project.logger.lifecycle("Python 3 detected.")
            } catch (e: Exception) {
                project.logger.lifecycle("No python 3 detected.")
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("python", "-V"))
                    if (proc.waitFor() != 0) throw Exception("python failed")
                    spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", "python")
                    project.logger.lifecycle("Python detected.")
                } catch (e: Exception) {
                    throw GradleException("No any python version detected. You should install the python first to compile project.")
                }
            }
            // https://developer.android.com/guide/practices/page-sizes#other-build-systems
            spec.environment("RUST_ANDROID_GRADLE_CC_LINK_ARG", "-Wl,-z,max-page-size=16384,-soname,lib$libname.so")
            spec.environment("RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY", "$projectDir/$module/../linker-wrapper.py")
            spec.environment("RUST_ANDROID_GRADLE_TARGET", "target/${toolchain.target}/$profile/lib$libname.so")

            val ndkDir = android.ndkDirectory.absolutePath
            if (ndkDir.isNotEmpty()) {
                val hostOs = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "windows-x86_64" else "linux-x86_64"
                val llvmBin = "$ndkDir/toolchains/llvm/prebuilt/$hostOs/bin"
                val arPath = "$llvmBin/llvm-ar${if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""}"
                spec.environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_AR", arPath)
                spec.environment("CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_AR", arPath)
                spec.environment("CARGO_TARGET_I686_LINUX_ANDROID_AR", arPath)
                spec.environment("CARGO_TARGET_X86_64_LINUX_ANDROID_AR", arPath)
            }
        }
    }
}

tasks.whenTaskAdded {
    when (name) {
        "mergeDebugJniLibFolders", "mergeReleaseJniLibFolders" -> {
            dependsOn("cargoBuild")
            inputs.dir(layout.buildDirectory.dir("rustJniLibs/android"))
        }
    }
}

tasks.register<Exec>("cargoClean") {
    executable("cargo")     // cargo.cargoCommand
    args("clean")
    workingDir("$projectDir/${cargo.module}")
}
tasks.clean.dependsOn("cargoClean")

dependencies {
    api(project(":plugin"))
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.livedata.core.ktx)
    api(libs.androidx.preference)
    api(libs.androidx.room.runtime)
    api(libs.androidx.work.multiprocess)
    api(libs.androidx.work.runtime.ktx)
    api(libs.dnsjava)
    api(libs.firebase.analytics)
    api(libs.firebase.crashlytics)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.play.services)
    api(libs.material)
    api(libs.play.services.oss.licenses)
    api(libs.timber)
    coreLibraryDesugaring(libs.desugar)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit.ktx)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
}
