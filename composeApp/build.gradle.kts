import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionName = providers.gradleProperty("goldenpaw.versionName").getOrElse("1.0.0")
val appVersionCode = providers.gradleProperty("goldenpaw.versionCode").getOrElse("1").toInt()

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("androidx.compose.foundation.layout.ExperimentalLayoutApi")
            languageSettings.optIn("androidx.compose.animation.ExperimentalSharedTransitionApi")
            languageSettings.optIn("androidx.compose.foundation.ExperimentalFoundationApi")
            languageSettings.optIn("androidx.compose.animation.ExperimentalAnimationApi")
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.core.viewmodel)
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.glance.appwidget)
            implementation(libs.androidx.glance.material3)
            implementation(libs.koin.android)
            implementation(libs.kotlinx.coroutines.android)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "com.goldenpaw"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goldenpaw.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a release build can be side-loaded for perf testing.
            // Replace with a real upload key before publishing to Play.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/previous-compilation-data.bin")
    }
}

compose.desktop {
    application {
        mainClass = "com.goldenpaw.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "GoldenPaw"
            packageVersion = appVersionName.substringBefore("-")
            description = "Senior pet care companion"
            vendor = "GoldenPaw"
        }
    }
}
