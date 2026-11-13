plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ocfox.ims"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "dev.ocfox.ims"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("versionCode")
            .map(String::toInt)
            .getOrElse(1)
        versionName = providers.gradleProperty("versionName")
            .getOrElse("1.0.0")

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    val releaseKeystoreFile = providers.environmentVariable("SIGNING_KEYSTORE_PATH")
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
        ?.takeIf { it.isFile }
    val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
    val hasReleaseSigning = releaseKeystoreFile != null &&
        !releaseStorePassword.isNullOrEmpty() &&
        !releaseKeyAlias.isNullOrEmpty() &&
        !releaseKeyPassword.isNullOrEmpty()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.extended)

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
