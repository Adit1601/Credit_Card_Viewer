import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Release signing — reads from ~/keys/keystore.properties. Kept OUTSIDE the repo so no
// editor save under the project root can accidentally expose the password. Must contain:
// storeFile (absolute path), storePassword, keyAlias, keyPassword.
// If the file is absent, release builds are produced UNSIGNED so a missing config never
// silently falls back to the debug key.
val keystorePropertiesFile = File(System.getProperty("user.home"), "keys/keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseKeystore) FileInputStream(keystorePropertiesFile).use { load(it) }
}

android {
    namespace = "com.cardvault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cardvault"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { useSupportLibrary = true }

        // Keeps x86/x86_64 out of the build entirely. `splits.abi.include` below only filters the
        // per-ABI outputs — the universal APK ignores it and would otherwise still carry all four
        // ABIs (43 MB). Both knobs are needed; see the splits block for the reasoning.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                // Trim every value — Properties.load() preserves trailing whitespace on
                // values, and a stray space in the editor otherwise turns into a mystery
                // "file not found" or an incorrect-password rejection at signing time.
                storeFile = file(keystoreProperties.getProperty("storeFile").trim())
                storePassword = keystoreProperties.getProperty("storePassword").trim()
                keyAlias = keystoreProperties.getProperty("keyAlias").trim()
                keyPassword = keystoreProperties.getProperty("keyPassword").trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Bundled ML Kit ships a ~7-11 MB native OCR pipeline PER ABI, so a universal APK carrying
    // all four is 43 MB against a 2.2 MB baseline. Splitting by ABI means a device downloads only
    // its own slice: 14.5 MB on arm64-v8a, 10.5 MB on armeabi-v7a.
    //
    // x86/x86_64 are deliberately excluded — they accounted for 22 MB of that 43 MB, no shipping
    // Android phone uses them, and this project's emulator images are arm64. If you ever need an
    // x86_64 emulator, add it back here rather than reaching for the universal APK.
    //
    // The universal APK is still produced as a fallback for handing someone a single file that
    // works on any arm device; for normal installs prefer the per-ABI output.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// ---------------------------------------------------------------------------------------------
// Scan-feature invariants, enforced at build time.
//
// These are properties of the card scanner that are easy to state, easy to break by accident, and
// invisible in a code review six months from now. A grep gate wired into `preBuild` means breaking
// one fails the build with the offending line rather than shipping quietly.
//
// Run on its own with: ./gradlew :app:verifyScanInvariants
// ---------------------------------------------------------------------------------------------
val scanPureDir = layout.projectDirectory.dir("src/main/java/com/cardvault/scan")
val scanUiDir = layout.projectDirectory.dir("src/main/java/com/cardvault/ui/scan")

val verifyScanInvariants = tasks.register("verifyScanInvariants") {
    group = "verification"
    description = "Asserts the scanner never logs card data, never persists a frame, and keeps com.cardvault.scan platform-free."

    // Declared as inputs so the task is up-to-date while nothing under either package changes.
    inputs.dir(scanPureDir).withPropertyName("scanPure")
    inputs.dir(scanUiDir).withPropertyName("scanUi")

    // ...which needs an output to be true of. A task that declares none can never be up to date,
    // however precise its inputs, so the sweep in fact re-ran on every build — and since it hangs
    // off `preBuild`, that is every build. The marker's contents are irrelevant; its existence
    // next to an unchanged input snapshot is the entire signal. `clean` removes it, so a clean
    // build verifies again.
    val marker = layout.buildDirectory.file("verification/scan-invariants.ok")
    outputs.file(marker).withPropertyName("marker")

    val pure = scanPureDir.asFile
    val ui = scanUiDir.asFile
    val markerFile = marker.get().asFile

    doLast {
        val failures = mutableListOf<String>()

        fun sweep(root: File, label: String, rules: List<Pair<Regex, String>>) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    // Comments are skipped: the reasoning for these rules is written *in* the
                    // files, and a KDoc saying "no Log calls here" must not trip its own rule.
                    val code = line.trimStart()
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                        return@forEachIndexed
                    }
                    rules.forEach { (pattern, why) ->
                        if (pattern.containsMatchIn(line)) {
                            failures += "$label ${f.name}:${i + 1} — $why\n        ${line.trim()}"
                        }
                    }
                }
            }
        }

        // Card numbers must never reach logcat. On a rooted or developer-enabled device logcat is
        // readable, and a PAN in a crash log outlives the process that wrote it.
        val noLogging = listOf(
            Regex("\\bLog\\.[vdiwe]\\s*\\(") to "logs from the scan path (a PAN in logcat outlives the app)",
            Regex("\\bprintln\\s*\\(") to "prints from the scan path",
            Regex("\\bSystem\\.(out|err)\\b") to "writes to stdout/stderr from the scan path"
        )

        // com.cardvault.scan is the pure-JVM parser: unit-testable with no device and no
        // Robolectric, which is the only reason the extraction algorithm has fast tests at all.
        val noPlatform = listOf(
            Regex("^\\s*import\\s+(android|androidx|com\\.google)\\.") to
                "imports a platform type into the pure-JVM parser package — put it in com.cardvault.ui.scan"
        )

        // No code path may write a frame anywhere. The capture use cases that could are never
        // constructed, and neither is any file sink.
        val noPersistence = listOf(
            Regex("\\b(ImageCapture|VideoCapture|Recorder|Recording)\\b") to
                "references a capture use case — the scanner may only ever bind Preview + ImageAnalysis",
            Regex("\\b(FileOutputStream|FileWriter|createTempFile|MediaStore|toBitmap\\(|\\.compress\\()") to
                "could write frame data to storage"
        )

        sweep(pure, "[scan]", noLogging + noPlatform + noPersistence)
        sweep(ui, "[ui.scan]", noLogging + noPersistence)

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Scan-feature invariants violated:\n      - " + failures.joinToString("\n      - ")
            )
        }

        // Only on the way out, so a violation leaves no marker to be mistaken for a pass.
        markerFile.parentFile.mkdirs()
        markerFile.writeText("scan invariants verified\n")
    }
}

tasks.named("preBuild") { dependsOn(verifyScanInvariants) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
