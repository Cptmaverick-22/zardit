plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "io.github.cptmaverick_22.zardit"
  compileSdk = 36
  val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
  val releaseKeystorePass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
  val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
  val releaseKeyPass = System.getenv("ANDROID_KEY_PASSWORD")
  val hasReleaseSigning = releaseKeystoreFile != null &&
    !releaseKeystorePass.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPass.isNullOrBlank()

  val gitCommit = (System.getenv("ZARDIT_GIT_COMMIT") ?: "").take(40)
  val buildTimeSec = System.getenv("ZARDIT_BUILD_TIME_SEC")?.toLongOrNull() ?: 0L

  defaultConfig {
    applicationId = "io.github.cptmaverick_22.zardit"
    minSdk = 24
    targetSdk = 36
    versionCode = 10
    versionName = "1.5.1"
    buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    buildConfigField("long", "BUILD_TIME_SEC", "${buildTimeSec}L")
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = releaseKeystorePass
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPass
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = false
      signingConfig = if (hasReleaseSigning) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debug")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a")
      isUniversalApk = false
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)

  // Zardit core: yt-dlp + thumbnails + SAF + Media3 pure-Java MP4 muxing (no bundled FFmpeg)
  implementation(libs.youtubedl.library)
  implementation(libs.coil.compose)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.media3.muxer)
  implementation(libs.androidx.media3.extractor)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
