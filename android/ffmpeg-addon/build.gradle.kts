plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "io.github.cptmaverick_22.zardit.ffmpeg"
  compileSdk = 36

  val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
  val releaseKeystorePass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
  val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
  val releaseKeyPass = System.getenv("ANDROID_KEY_PASSWORD")
  val hasReleaseSigning = releaseKeystoreFile != null &&
    !releaseKeystorePass.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPass.isNullOrBlank()

  defaultConfig {
    applicationId = "io.github.cptmaverick_22.zardit.ffmpeg"
    minSdk = 24
    targetSdk = 36
    versionCode = 10
    versionName = "1.5.1"
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
    buildConfig = true
    aidl = false
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

dependencies {
  implementation(libs.youtubedl.ffmpeg)
}
