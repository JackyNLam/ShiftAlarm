plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.shiftalarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.shiftalarm"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Persistent CI signing. When KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD
    // are present (fed from GitHub Secrets in .github/workflows/build.yml), BOTH debug and
    // release APKs are signed with that keystore, so a new build installs over the previous
    // one without uninstalling. Without them, builds fall back to the runner's debug key,
    // which is freshly generated per GitHub run — a different signature every time, so
    // Android rejects updates (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and data is lost.
    val keystorePath = System.getenv("KEYSTORE_PATH")
    val hasCiKeystore = !keystorePath.isNullOrBlank()
    if (hasCiKeystore) {
        val missingSecrets = listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
            .filter { System.getenv(it).isNullOrBlank() }
        if (missingSecrets.isNotEmpty()) {
            throw GradleException(
                "KEYSTORE_PATH is set but ${missingSecrets.joinToString(", ")} is missing. " +
                    "Add the missing GitHub Secrets, then re-run the workflow."
            )
        }
    }
    val ciSigningConfig = if (hasCiKeystore) "ci" else "debug"

    signingConfigs {
        if (hasCiKeystore) {
            create("ci") {
                val keystoreFile = rootProject.file(keystorePath!!)
                storeFile = keystoreFile
                // The workflow always decodes the secret to a single fixed filename, so
                // sniff the container header to support both keytool .jks (JKS magic)
                // and openssl-generated PKCS12 keystores (DER starts with 0x30).
                val firstByte = keystoreFile.inputStream().use { it.readNBytes(4).firstOrNull() }
                storeType = if (firstByte != null && firstByte.toInt() == 0x30) "PKCS12" else "JKS"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName(ciSigningConfig)
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(ciSigningConfig)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}