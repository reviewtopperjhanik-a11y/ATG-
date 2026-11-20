import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.applet.kyrbxy"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  fun readBuildConfigValue(key: String, devKey: String? = null, isRelease: Boolean): String {
    if (!isRelease && devKey != null) {
      val devVal = providers.gradleProperty(devKey).orNull
        ?: providers.environmentVariable(devKey).orNull
      if (!devVal.isNullOrBlank()) return devVal.trim()
    }
    val prodVal = providers.gradleProperty(key).orNull
      ?: providers.environmentVariable(key).orNull
    return prodVal?.trim().orEmpty()
  }

  fun escapeBuildConfigLiteral(raw: String): String =
    "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
      val prodManifestUrl = readBuildConfigValue("AGT_RUNTIME_MANIFEST_URL", isRelease = true)
      val prodGitHubClientId = readBuildConfigValue("AGT_GITHUB_OAUTH_CLIENT_ID", isRelease = true)
      buildConfigField("String", "AGT_RUNTIME_MANIFEST_URL", escapeBuildConfigLiteral(prodManifestUrl))
      buildConfigField("String", "AGT_GITHUB_OAUTH_CLIENT_ID", escapeBuildConfigLiteral(prodGitHubClientId))
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
      val devManifestUrl = readBuildConfigValue(
        key = "AGT_RUNTIME_MANIFEST_URL",
        devKey = "AGT_RUNTIME_MANIFEST_URL_DEV",
        isRelease = false,
      )
      val devGitHubClientId = readBuildConfigValue(
        key = "AGT_GITHUB_OAUTH_CLIENT_ID",
        devKey = "AGT_GITHUB_OAUTH_CLIENT_ID_DEV",
        isRelease = false,
      )
      buildConfigField("String", "AGT_RUNTIME_MANIFEST_URL", escapeBuildConfigLiteral(devManifestUrl))
      buildConfigField("String", "AGT_GITHUB_OAUTH_CLIENT_ID", escapeBuildConfigLiteral(devGitHubClientId))
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.register("validateReleaseConfig") {
  group = "verification"
  description = "Validates required public production configuration (AGT_RUNTIME_MANIFEST_URL and AGT_GITHUB_OAUTH_CLIENT_ID) and verifies no GitHub OAuth client secret is embedded."
  val manifestProp = providers.gradleProperty("AGT_RUNTIME_MANIFEST_URL")
    .orElse(providers.environmentVariable("AGT_RUNTIME_MANIFEST_URL"))
    .orElse("")
  val clientIdProp = providers.gradleProperty("AGT_GITHUB_OAUTH_CLIENT_ID")
    .orElse(providers.environmentVariable("AGT_GITHUB_OAUTH_CLIENT_ID"))
    .orElse("")
  val forbiddenSecretProp = providers.gradleProperty("AGT_GITHUB_OAUTH_CLIENT_SECRET")
    .orElse(providers.environmentVariable("AGT_GITHUB_OAUTH_CLIENT_SECRET"))
    .orElse(providers.gradleProperty("GITHUB_OAUTH_CLIENT_SECRET"))
    .orElse(providers.environmentVariable("GITHUB_OAUTH_CLIENT_SECRET"))
    .orElse("")

  doLast {
    val manifest = manifestProp.get().trim()
    val clientId = clientIdProp.get().trim()
    val forbiddenSecret = forbiddenSecretProp.get().trim()
    val errors = mutableListOf<String>()
    if (forbiddenSecret.isNotEmpty()) {
      errors += "Forbidden secret detected (AGT_GITHUB_OAUTH_CLIENT_SECRET / GITHUB_OAUTH_CLIENT_SECRET). Never embed a GitHub OAuth Client Secret in the APK."
    }
    if (manifest.isEmpty()) {
      errors += "AGT_RUNTIME_MANIFEST_URL is missing. Supply the official HTTPS runtime manifest endpoint before production release."
    } else if (!manifest.startsWith("https://", ignoreCase = true) ||
      manifest.contains("example.com", ignoreCase = true) ||
      manifest.contains("example.org", ignoreCase = true) ||
      manifest.contains("placeholder", ignoreCase = true)
    ) {
      errors += "AGT_RUNTIME_MANIFEST_URL must be an official non-placeholder HTTPS URL."
    }
    if (clientId.isEmpty()) {
      errors += "AGT_GITHUB_OAUTH_CLIENT_ID is missing. Supply the official GitHub OAuth App Client ID before production release."
    } else if (clientId.startsWith("ghp_") ||
      clientId.startsWith("ghs_") ||
      clientId.startsWith("github_pat_") ||
      clientId.contains("placeholder", ignoreCase = true) ||
      clientId.contains("...")
    ) {
      errors += "AGT_GITHUB_OAUTH_CLIENT_ID is invalid or resembles a private secret/placeholder."
    }
    if (errors.isNotEmpty()) {
      throw GradleException("AGT Release Configuration Check Failed:\n - " + errors.joinToString("\n - "))
    }
    logger.lifecycle("AGT Release Configuration verified: AGT_RUNTIME_MANIFEST_URL and AGT_GITHUB_OAUTH_CLIENT_ID are configured.")
  }
}

val strictReleaseConfig = providers.gradleProperty("AGT_STRICT_RELEASE_CONFIG")
  .orElse(providers.environmentVariable("AGT_STRICT_RELEASE_CONFIG"))
  .map { it.equals("true", ignoreCase = true) }
  .getOrElse(false)

if (strictReleaseConfig) {
  tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn("validateReleaseConfig")
  }
}
