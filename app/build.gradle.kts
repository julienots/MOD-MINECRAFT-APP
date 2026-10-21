import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bedrockmodstudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bedrockmodstudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Clé de DEBUG partagée (non secrète, mot de passe « android ») : tous les APK de test ont la même
        // signature, ce qui permet d'installer une nouvelle version par-dessus l'ancienne sans perdre ses projets.
        // Elle ne doit pas servir à publier sur le Play Store.
        getByName("debug") {
            storeFile = file("debug-signing/debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Signature de publication fournie par variables d'environnement (secrets GitHub), jamais dans le dépôt.
        val ksPath = System.getenv("BMS_KEYSTORE_PATH")
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("BMS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BMS_KEY_ALIAS")
                keyPassword = System.getenv("BMS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.environment("LC_ALL", "C.UTF-8")
            it.jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8", "-Xmx2g")
            // Option : image Android de Robolectric déjà téléchargée (environnements sans accès direct à Maven Central).
            System.getenv("ROBOLECTRIC_DEPS_DIR")?.let { dir ->
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", dir)
            }
        }
    }

    lint {
        // Vérifie aussi le module core : il doit n'utiliser que des API Java disponibles sur Android 8 (API 26).
        checkDependencies = true
        abortOnError = true
        warningsAsErrors = false
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
        disable += "NewerVersionAvailable"
        // Textes en français : le correcteur anglais et la suggestion de pluriels anglais ne s'appliquent pas.
        disable += "Typos"
        disable += "PluralsCandidate"
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/INDEX.LIST")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    // Assistant IA facultatif : SDK officiel Anthropic, utilisé seulement si l'utilisateur fournit sa propre clé.
    implementation(libs.anthropic.java)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
