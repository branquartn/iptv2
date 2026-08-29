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
val appVersionCode = 61
val appVersionName = "1.0.60"
// Changelog affiché dans le modal de mise à jour OTA.
// ⚠️ OUBLIÉ PENDANT ~15 VERSIONS (29/08/2026, bug signalé par l'utilisateur :
// "le texte de la maj n'est pas le bon") — appVersionCode/appVersionName ont
// été bumpés à chaque commit de ce jour-là sans jamais toucher cette ligne,
// donc chaque "Mise à jour disponible" affichait un texte obsolète (une
// modif du 28/08 alors qu'on en était à v1.0.50). Ce commentaire ne suffit
// visiblement pas tout seul à s'en souvenir : à chaque bump de version,
// updater CETTE ligne AVANT de commit, pas après coup.
val appChangelog = "Fenêtre de chargement : cercle de progression avec pourcentage animé en temps réel, plus fidèle qu'avant."

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
    namespace = "com.nicotv.iptv2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nicotv.iptv2"
        minSdk = 21
        targetSdk = 36
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
            storeFile = file("iptv2-release.jks")
            storePassword = secretProperty("iptv2StorePassword", "IPTV2_STORE_PASSWORD")
            keyAlias = secretProperty("iptv2KeyAlias", "IPTV2_KEY_ALIAS").ifBlank { "iptv2" }
            keyPassword = secretProperty("iptv2KeyPassword", "IPTV2_KEY_PASSWORD")
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
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefresh)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.splashscreen)

    // Media3 / ExoPlayer (HLS pour les flux live IPTV, en plus du MP4/TS direct)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.process)

    // Coroutines
    implementation(libs.coroutines.android)

    // Network (Xtream Codes API + M3U par URL)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Images
    implementation(libs.coil)

    // Room (cache local de la playlist chargée)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore (config de la source playlist active)
    implementation(libs.datastore.preferences)
}

tasks.register("publishReleaseToIptv2Update") {
    group = "publishing"
    description = "Copie l'APK release et version.json dans le dossier update iptv2.nicotv.ovh."
    dependsOn("assembleRelease")

    // Valeurs capturées au moment de la configuration (compatible configuration cache :
    // le doLast ne référence plus project, providers, layout ni logger).
    val versionCode = appVersionCode
    val versionName = appVersionName
    val changelog = appChangelog
    val updateDirPath = providers.gradleProperty("iptv2UpdateDir")
        .orElse(providers.environmentVariable("IPTV2_UPDATE_DIR"))
        .orElse("Z:/update-iptv2")
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

        val apkName = "iptv2-$versionName.apk"
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
              "apkUrl": "https://iptv2.nicotv.ovh/update/$apkName",
              "changelog": "$changelogEscaped"
            }
            """.trimIndent() + "\n"
        )

        println("iptv2 update publie: ${targetApk.absolutePath}")
    }
}

tasks.register("publishBundleToReleases") {
    group = "publishing"
    description = "Copie le .aab release dans le dossier privé des releases (upload manuel Play Console)."
    dependsOn("bundleRelease")

    val versionName = appVersionName
    val releasesDirPath = providers.gradleProperty("iptv2ReleasesDir")
        .orElse(providers.environmentVariable("IPTV2_RELEASES_DIR"))
        .orElse("Z:/releases")
    val sourceAabFile = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")

    doLast {
        val releasesDir = File(releasesDirPath.get())
        if (!releasesDir.exists() && !releasesDir.mkdirs()) {
            throw GradleException("Dossier releases introuvable: ${releasesDir.absolutePath}")
        }

        val sourceAab = sourceAabFile.get().asFile
        if (!sourceAab.isFile) {
            throw GradleException("AAB release introuvable: ${sourceAab.absolutePath}")
        }

        val targetAab = releasesDir.resolve("iptv2-$versionName.aab")
        sourceAab.copyTo(targetAab, overwrite = true)
        targetAab.setReadable(true, false)

        println("iptv2 bundle publie: ${targetAab.absolutePath}")
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("publishReleaseToIptv2Update")
    }
    tasks.named("bundleRelease") {
        finalizedBy("publishBundleToReleases")
    }
}
