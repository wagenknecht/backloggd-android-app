import com.android.build.api.variant.ResValue
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The release key lives outside the repo; its location and passwords come from local.properties.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val releaseStoreFile: String? = localProperties.getProperty("release.storeFile")

/**
 * Copies the changelog of the version being built into the APK as changelog.txt, so the app can
 * show it after an update without asking GitHub. A missing changelog only produces a warning.
 */
abstract class BundleChangelogTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelog: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        val source = changelog.files.firstOrNull { it.isFile }
        if (source == null) {
            logger.warn("No changelog found at ${changelog.files.joinToString()}")
            return
        }
        source.copyTo(File(target, "changelog.txt"))
    }
}

android {
    namespace = "de.wagenknecht.backloggd"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "de.wagenknecht.backloggd"
        minSdk = 24
        targetSdk = 36
        // Raise versionCode with every release and add changelogs/<versionCode>.txt under fastlane/.
        versionCode = 3
        versionName = "2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
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
            // Never fall back to the debug key: installed copies could not be updated with it.
            // Without a configured release key the build simply stays unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Backloggd Debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Changelogs follow the F-Droid layout: one plain-text file per versionCode, at most 500 characters.
androidComponents {
    onVariants { variant ->
        val versionCode = android.defaultConfig.versionCode
        val bundleChangelog = tasks.register<BundleChangelogTask>(
            "bundle${variant.name.replaceFirstChar { it.uppercase() }}Changelog"
        ) {
            changelog.from(rootProject.file("fastlane/metadata/android/en-US/changelogs/$versionCode.txt"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundleChangelog, BundleChangelogTask::outputDir)

        // Launcher shortcuts name their target package literally, and debug builds carry a suffix.
        variant.resValues.put(
            variant.makeResValueKey("string", "shortcut_target_package"),
            variant.applicationId.map { ResValue(it) }
        )
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.preference)
    implementation(libs.work.runtime)
    implementation(libs.swiperefreshlayout)
    implementation(libs.core.splashscreen)
    implementation(libs.jsoup)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}