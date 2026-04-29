import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val rustProjectDir = file("src/main/rust/hoshiepub")
val uniffiOutDir = layout.buildDirectory.dir("generated/source/uniffi/main/kotlin").get().asFile
val rustJniLibsDir = layout.buildDirectory.dir("jniLibs").get().asFile
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val isReleaseSigningRequested = releaseSigningValues.any { !it.isNullOrBlank() }
val isReleaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() } &&
    releaseKeystorePath?.let { file(it).isFile } == true

if (isReleaseSigningRequested && !isReleaseSigningConfigured) {
    throw GradleException(
        "Release signing requires ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, " +
            "ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD, and the keystore file must exist."
    )
}

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("win")
val sdkDir = localProperties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("ANDROID_HOME").orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull?.takeIf { it.isNotBlank() }
fun androidRevisionParts(directory: File): List<Int> =
    Regex("\\d+").findAll(directory.name).map { it.value.toInt() }.toList()

fun compareAndroidRevisions(left: File, right: File): Int {
    val leftParts = androidRevisionParts(left)
    val rightParts = androidRevisionParts(right)
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val comparison = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return left.name.compareTo(right.name)
}

val androidNdkHome = providers.environmentVariable("ANDROID_NDK_HOME").orNull?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("ndk.dir")?.takeIf { it.isNotBlank() }
    ?: sdkDir
        ?.let { file("$it/ndk") }
        ?.takeIf { it.isDirectory }
        ?.listFiles()
        ?.filter { it.isDirectory }
        ?.maxWithOrNull(::compareAndroidRevisions)
        ?.absolutePath
    ?: "/opt/homebrew/share/android-ndk"
val cargoBinaryName = if (isWindows) "cargo.exe" else "cargo"
val cargo = providers.environmentVariable("CARGO").orNull?.takeIf { it.isNotBlank() }
    ?: listOfNotNull(
        System.getenv("HOME"),
        System.getenv("USERPROFILE"),
    ).asSequence()
        .map { file("$it/.cargo/bin/$cargoBinaryName") }
        .firstOrNull { it.isFile }
        ?.absolutePath
    ?: cargoBinaryName

val hostLibExtension = when {
    osName.contains("mac") -> "dylib"
    isWindows -> "dll"
    else -> "so"
}
val hostLibName = when {
    isWindows -> "hoshiepub.$hostLibExtension"
    else -> "libhoshiepub.$hostLibExtension"
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
        versionName = "0.1.0"

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

    if (isReleaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(libs.androidx.compose.material.icons.extended)
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

    val hostLibPath = rustProjectDir.resolve("target/debug/$hostLibName")

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
