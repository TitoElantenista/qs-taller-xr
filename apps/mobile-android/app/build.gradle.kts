import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Provider details for Impressum and Datenschutzerklärung; see ../legal.properties.
val legal = Properties().apply {
    rootProject.file("legal.properties").takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use(::load)
}
// The owner explicitly requested omission of the postal address from the published app.
val legalRequired = listOf("provider.name", "provider.email", "privacy.url")

// Upload key for Google Play; see ../keystore.properties.example.  Never committed.
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use(::load)
}
fun signingPassword(key: String): String? = keystore.getProperty("${key}File")
    ?.takeIf(String::isNotBlank)
    ?.let { rootProject.file(it).readLines(Charsets.UTF_8).firstOrNull() }
    ?: keystore.getProperty(key)
val uploadStorePassword = signingPassword("storePassword")
val uploadKeyPassword = signingPassword("keyPassword")
val releaseSigningConfigured = listOf(keystore.getProperty("storeFile"), keystore.getProperty("keyAlias"),
    uploadStorePassword, uploadKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.qs.tallerxr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qs.tallerxr"
        minSdk = 28
        targetSdk = 36
        versionCode = 78
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        listOf(
            "provider.name", "provider.street", "provider.city", "provider.country", "provider.email",
            "provider.phone", "provider.representative", "provider.register", "provider.vatId",
            "privacy.url", "privacy.date",
        ).forEach { key ->
            val value = legal.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "LEGAL_" + key.uppercase().replace('.', '_'), "\"$value\"")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("upload") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = uploadStorePassword
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "BUNDLED_DEMO_ENABLED", "true")
            // The experimental tablet adapter is retained only in local debug builds.
            buildConfigField("boolean", "HUAWEI_AR_ENABLED", providers.gradleProperty("huaweiAr").orElse("true").get().toBoolean().toString())
            // The experimental Snap is hidden since 0.27.1; -PsnapExperiment=true shows it again for work on it.
            buildConfigField("boolean", "SNAP_EXPERIMENT_ENABLED", providers.gradleProperty("snapExperiment").orElse("false").get().toBoolean().toString())
        }
        release {
            buildConfigField("boolean", "BUNDLED_DEMO_ENABLED", "false")
            buildConfigField("boolean", "HUAWEI_AR_ENABLED", "false")
            buildConfigField("boolean", "SNAP_EXPERIMENT_ENABLED", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("upload")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { noCompress += listOf("glb", "qsxr", "filamat", "ktx") }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Huawei AR Engine, debug builds only.  A local copy in libs/ wins; otherwise the identical
    // artifact (same SHA-256) comes from Huawei's Maven repository.  It is not part of the repository.
    val huaweiArAar = file("libs/arenginesdk-4.0.0.5.aar")
    if (huaweiArAar.isFile) debugImplementation(files(huaweiArAar)) else debugImplementation("com.huawei.hms:arenginesdk:4.0.0.5")
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.github.sceneview:sceneview:4.18.0")
    implementation("io.github.sceneview:arsceneview:4.18.0")
    testImplementation("junit:junit:4.13.2")
}

// Require the supplied publisher/contact and public privacy URL for a signed store build.
gradle.taskGraph.whenReady {
    val storeBuild = allTasks.any { it.project == project && it.name in setOf("bundleRelease", "assembleRelease") }
    if (storeBuild) {
        val missing = legalRequired.filter { legal.getProperty(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("legal.properties ist unvollständig: ${missing.joinToString()} fehlen.")
        }
        if (!releaseSigningConfigured) {
            throw GradleException("keystore.properties fehlt oder ist unvollständig (siehe keystore.properties.example).")
        }
    }
}
