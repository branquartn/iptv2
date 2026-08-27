import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Version centralisée : référencée dans defaultConfig ET dans la tâche de publication
// (évite l'accès à android.defaultConfig depuis une tâche, qui force l'ancienne DSL).
val appVersionCode = 292
val appVersionName = "1.0.11.83"
// Changelog affiché dans le modal de mise à jour OTA — mis à jour à chaque bump
// (avant : texte générique en dur, jamais le vrai détail du fix apporté).
val appChangelog = "Télécommande : molette D-pad plus grande et non rognée, pavé souris agrandi, sélection d'appareil plus facile à viser."

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun secretProperty(localName: String, envName: String): String =
    (findProperty(localName) as String?)
        ?: localProperties.getProperty(localName)
        ?: providers.environmentVariable(envName).orNull
        ?: ""

android {
    namespace = "com.nicotv.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nicotv.iptv"
        minSdk = 21
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        // Limite aux architectures ARM réelles (téléphones, Android TV, Fire TV).
        // On retire x86/x86_64 (émulateurs uniquement) pour alléger l'APK.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("nicotv-release.jks")
            storePassword = secretProperty("nicotvStorePassword", "NICOTV_STORE_PASSWORD")
            keyAlias = secretProperty("nicotvKeyAlias", "NICOTV_KEY_ALIAS").ifBlank { "nicotv" }
            keyPassword = secretProperty("nicotvKeyPassword", "NICOTV_KEY_PASSWORD")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefresh)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.splashscreen)

    // Media3 / ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.process)

    // Coroutines
    implementation(libs.coroutines.android)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Images
    implementation(libs.coil)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Navigation
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)

    // DataStore
    implementation(libs.datastore.preferences)

}

tasks.register("publishReleaseToNicoUpdate") {
    group = "publishing"
    description = "Copie l'APK release et version.json dans le dossier update NicoTV."
    dependsOn("assembleRelease")

    // Valeurs capturées au moment de la configuration (compatible configuration cache :
    // le doLast ne référence plus project, providers, layout ni logger).
    val versionCode = appVersionCode
    val versionName = appVersionName
    val changelog = appChangelog
    val updateDirPath = providers.gradleProperty("nicotvUpdateDir")
        .orElse(providers.environmentVariable("NICOTV_UPDATE_DIR"))
        .orElse("Z:/update")
    val sourceApkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk")

    doLast {
        val updateDir = File(updateDirPath.get())
        if (!updateDir.exists() && !updateDir.mkdirs()) {
            throw GradleException("Dossier update introuvable: ${updateDir.absolutePath}")
        }

        val sourceApk = sourceApkFile.get().asFile
        if (!sourceApk.isFile) {
            throw GradleException("APK release introuvable: ${sourceApk.absolutePath}")
        }

        val apkName = "iptv-$versionName.apk"
        val targetApk = updateDir.resolve(apkName)
        sourceApk.copyTo(targetApk, overwrite = true)
        targetApk.setReadable(true, false)
        targetApk.setWritable(true, false)
        targetApk.setExecutable(true, false)

        val changelogEscaped = changelog.replace("\\", "\\\\").replace("\"", "\\\"")
        updateDir.resolve("version.json").writeText(
            """
            {
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "apkUrl": "https://update.nicotv.ovh/$apkName",
              "changelog": "$changelogEscaped"
            }
            """.trimIndent() + "\n"
        )

        println("NicoTV update publie: ${targetApk.absolutePath}")
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("publishReleaseToNicoUpdate")
    }
}

