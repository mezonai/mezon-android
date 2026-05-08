import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
}

val mezonSecretsFile = rootProject.file("mezon.secrets.properties")
if (!mezonSecretsFile.exists()) {
    throw GradleException(
        "Missing mezon/mezon.secrets.properties. Copy mezon/mezon.secrets.properties.example to mezon/mezon.secrets.properties and fill in values, or get the file from your team."
    )
}
val mezonSecrets: Properties = Properties().apply {
    mezonSecretsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.mezon.mobile"
    compileSdk = 35

    val signingPropsFile = rootProject.file("signing.properties")
    val signingProps = Properties().apply {
        if (signingPropsFile.exists()) {
            signingPropsFile.inputStream().use { load(it) }
        }
    }

    fun signingProp(name: String): String =
        requireNotNull(signingProps.getProperty(name)) {
            "Missing '$name' in signing.properties"
        }

    defaultConfig {
        applicationId = "com.mezon.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 2_000_000
        versionName = "1.1.176"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        fun esc(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"")
        fun req(name: String): String {
            val v = mezonSecrets.getProperty(name)?.trim()
            if (v.isNullOrEmpty()) {
                error("Missing or empty '$name' in mezon.secrets.properties")
            }
            return v
        }
        fun buildStr(name: String) {
            buildConfigField("String", name, "\"${esc(req(name))}\"")
        }
        fun buildBool(name: String) {
            val v = req(name).lowercase()
            val lit = when (v) {
                "true", "1", "yes" -> "true"
                "false", "0", "no" -> "false"
                else -> error("Invalid boolean for $name: $v (use true/false in mezon.secrets.properties)")
            }
            buildConfigField("Boolean", name, lit)
        }
        fun buildInt(name: String) {
            buildConfigField("int", name, req(name))
        }

        buildStr("MEZON_GATEWAY_URL")
        buildStr("MEZON_API_HOST")
        buildStr("MEZON_API_PORT")
        buildBool("MEZON_API_SECURE")
        buildStr("MEZON_API_KEY")
        buildStr("MEZON_API_CLIENT_KEY_CUSTOM")
        buildStr("MEZON_DOMAIN_URL")
        buildStr("MEZON_REDIRECT_URI")
        buildStr("MEZON_GOOGLE_PLAY_URL")
        buildStr("MEZON_OAUTH2_CLIENT_ID")
        buildStr("MEZON_OAUTH2_AUTHORIZE_URL")
        buildStr("MEZON_OAUTH2_REDIRECT_URI")
        buildStr("MEZON_OAUTH2_RESPONSE_TYPE")
        buildStr("MEZON_OAUTH2_SCOPE")
        buildStr("MEZON_OAUTH2_CODE_CHALLENGE_METHOD")
        buildStr("MEZON_OAUTH2_LOGOUT_URL")
        buildStr("MEZON_OAUTH2_LOGOUT_CALLBACK")
        buildStr("MEZON_GOOGLE_CLIENT_ID")
        buildStr("MEZON_FCM_API_KEY")
        buildStr("MEZON_FCM_AUTH_DOMAIN")
        buildStr("MEZON_FCM_PROJECT_ID")
        buildStr("MEZON_FCM_STORAGE_BUCKET")
        buildStr("MEZON_FCM_MESSAGING_SENDER_ID")
        buildStr("MEZON_FCM_APP_ID")
        buildStr("MEZON_FCM_MEASUREMENT_ID")
        buildStr("MEZON_FCM_VAPID_KEY")
        buildStr("MEZON_MEET_WS_URL")
        buildStr("MEZON_WEBRTC_ICESERVERS_URL")
        buildStr("MEZON_WEBRTC_ICESERVERS_USERNAME")
        buildStr("MEZON_WEBRTC_ICESERVERS_CREDENTIAL")
        buildStr("MEZON_STREAM_WS_URL")
        buildStr("MEZON_BASE_IMG_URL")
        buildStr("MEZON_LOGO_URL")
        buildStr("MEZON_IMGPROXY_BASE_URL")
        buildStr("MEZON_IMGPROXY_KEY")
        buildStr("MEZON_TENOR_KEY")
        buildStr("MEZON_TENOR_URL_CATEGORIES")
        buildStr("MEZON_TENOR_URL_SEARCH")
        buildStr("MEZON_TENOR_URL_FEATURED")
        buildStr("TENOR_API_KEY")
        buildStr("MEZON_SENTRY_DSN")
        buildStr("MEZON_NOTIFICATION_WS_URL")
        buildStr("MEZON_TREASURY_URL")
        buildStr("MEZON_TREASURY_KEY")
        buildStr("MEZON_TREASURY_URL_NETWORK")
        buildStr("MEZON_CONTRACT_ADDRESS")
        buildStr("MEZON_ANONYMOUS_USER_ID")
        buildInt("MEZON_MAX_LENGTH_NAME_ALLOWED")
        buildStr("MEZON_MMN_API_URL")
        buildStr("MEZON_ZK_API_URL")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (signingPropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(signingProp("MYAPP_RELEASE_STORE_FILE"))
                storePassword = signingProp("MYAPP_RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("MYAPP_RELEASE_KEY_ALIAS")
                keyPassword = signingProp("MYAPP_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-proto"))
    implementation(project(":mmn-client-kotlin"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging.interceptor)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Core
    implementation(libs.androidx.core.ktx)

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.fragment.ktx)


    // Media playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // CameraX + ZXing (QR)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // Lottie for animated stickers (TGS)
    implementation("com.airbnb.android:lottie:6.4.0")

    // LiveKit (voice/video channels)
    implementation(libs.livekit.android)
    implementation(libs.photoview)
    implementation(libs.androidx.appcompat)
    implementation("com.otaliastudios:zoomlayout:1.9.0")
    // WebRTC
    implementation(libs.google.webrtc)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
