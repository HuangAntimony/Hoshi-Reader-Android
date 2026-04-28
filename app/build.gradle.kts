plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val rustProjectDir = file("src/main/rust/hoshiepub")
val uniffiOutDir = layout.buildDirectory.dir("generated/source/uniffi/main/kotlin").get().asFile
val rustJniLibsDir = layout.buildDirectory.dir("jniLibs").get().asFile
val cargo = System.getenv("HOME") + "/.cargo/bin/cargo"
val androidNdkHome = System.getenv("ANDROID_NDK_HOME") ?: "/opt/homebrew/share/android-ndk"
val hostLibExtension = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "dylib"
    System.getProperty("os.name").lowercase().contains("win") -> "dll"
    else -> "so"
}

android {
    namespace = "moe.antimony.hoshi"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "moe.antimony.hoshi"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                targets += "hoshidicts_jni"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets["main"].java.directories.add(uniffiOutDir.absolutePath)
    sourceSets["main"].jniLibs.directories.add(rustJniLibsDir.absolutePath)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    testImplementation(libs.junit)
    testRuntimeOnly("net.java.dev.jna:jna:${libs.versions.jna.get()}@jar")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val buildRustHost by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    commandLine(cargo, "build")
}

val generateUniffiKotlin by tasks.registering(Exec::class) {
    dependsOn(buildRustHost)
    workingDir = rustProjectDir

    val hostLibPath = rustProjectDir.resolve("target/debug/libhoshiepub.$hostLibExtension")

    commandLine(
        cargo,
        "run",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        hostLibPath.absolutePath,
        "--language",
        "kotlin",
        "--out-dir",
        uniffiOutDir.absolutePath,
        "--no-format",
    )
}

val buildRustAndroidDebug by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    environment("ANDROID_NDK_HOME", androidNdkHome)
    commandLine(
        cargo,
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        rustJniLibsDir.absolutePath,
        "build",
        "--lib",
    )
}

val buildRustAndroidRelease by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    environment("ANDROID_NDK_HOME", androidNdkHome)
    commandLine(
        cargo,
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        rustJniLibsDir.absolutePath,
        "build",
        "--lib",
        "--release",
    )
}

tasks.named("preBuild") {
    dependsOn(generateUniffiKotlin)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiKotlin)
    source(uniffiOutDir)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(buildRustHost)
    systemProperty("jna.library.path", rustProjectDir.resolve("target/debug").absolutePath)
}

afterEvaluate {
    tasks.named("preDebugBuild") {
        dependsOn(buildRustAndroidDebug)
    }
    tasks.named("preReleaseBuild") {
        dependsOn(buildRustAndroidRelease)
    }
}
