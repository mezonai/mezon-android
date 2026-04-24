import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
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
        versionName = "1.1.175"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Gateway & API
        buildConfigField("String", "MEZON_GATEWAY_URL", "\"https://gw.mezon.ai\"")
        buildConfigField("String", "MEZON_API_HOST", "\"api.mezon.ai\"")
        buildConfigField("String", "MEZON_API_PORT", "\"443\"")
        buildConfigField("Boolean", "MEZON_API_SECURE", "true")
        buildConfigField("String", "MEZON_API_KEY", "\"HTTP3m3zonPr0dkey\"")
        buildConfigField("String", "MEZON_API_CLIENT_KEY_CUSTOM", "\"mezon.ai\"")
        buildConfigField("String", "MEZON_DOMAIN_URL", "\"https://mezon.ai\"")
        buildConfigField("String", "MEZON_REDIRECT_URI", "\"https://mezon.ai\"")
        buildConfigField("String", "MEZON_GOOGLE_PLAY_URL", "\"https://play.google.com/store/apps/details?id=com.mezon.mobile\"")

        // OAuth2
        buildConfigField("String", "MEZON_OAUTH2_CLIENT_ID", "\"25f63a1f-16b8-488b-8b14-68520eeab77f\"")
        buildConfigField("String", "MEZON_OAUTH2_AUTHORIZE_URL", "\"https://oauth2.mezon.ai/oauth2/auth\"")
        buildConfigField("String", "MEZON_OAUTH2_REDIRECT_URI", "\"http://127.0.0.1:4200/login/callback\"")
        buildConfigField("String", "MEZON_OAUTH2_RESPONSE_TYPE", "\"code\"")
        buildConfigField("String", "MEZON_OAUTH2_SCOPE", "\"openid+offline\"")
        buildConfigField("String", "MEZON_OAUTH2_CODE_CHALLENGE_METHOD", "\"S256\"")
        buildConfigField("String", "MEZON_OAUTH2_LOGOUT_URL", "\"https://oauth2.mezon.ai/oauth2/sessions/logout\"")
        buildConfigField("String", "MEZON_OAUTH2_LOGOUT_CALLBACK", "\"https://mezon.ai/logout/callback\"")
        buildConfigField("String", "MEZON_GOOGLE_CLIENT_ID", "\"391688022389-1k9kb377ea6dccpqii7m5pifjj0agsjc.apps.googleusercontent.com\"")

        // Firebase
        buildConfigField("String", "MEZON_FCM_API_KEY", "\"AIzaSyAzgF6LfHVWzlr9gGHWU7emix2768wSGHg\"")
        buildConfigField("String", "MEZON_FCM_AUTH_DOMAIN", "\"mezon-772fa.firebaseapp.com\"")
        buildConfigField("String", "MEZON_FCM_PROJECT_ID", "\"mezon-772fa\"")
        buildConfigField("String", "MEZON_FCM_STORAGE_BUCKET", "\"mezon-772fa.appspot.com\"")
        buildConfigField("String", "MEZON_FCM_MESSAGING_SENDER_ID", "\"285548761692\"")
        buildConfigField("String", "MEZON_FCM_APP_ID", "\"1:285548761692:web:3ca531af1deecee74e0c99\"")
        buildConfigField("String", "MEZON_FCM_MEASUREMENT_ID", "\"G-0WNQTXVMT3\"")
        buildConfigField("String", "MEZON_FCM_VAPID_KEY", "\"BLHZ5mS8qWRxw4Psmpq9QEavz1B8rYgmkWeJ9CCSDR-g-NjfYWpmfi_t2IV4dJLx2X76p2sApyISytUVtD64nfs\"")

        // WebRTC / Meet
        buildConfigField("String", "MEZON_MEET_WS_URL", "\"wss://meet.mezon.ai\"")
        buildConfigField("String", "MEZON_WEBRTC_ICESERVERS_URL", "\"turn:relay.mezon.ai:5349\"")
        buildConfigField("String", "MEZON_WEBRTC_ICESERVERS_USERNAME", "\"turnmezon\"")
        buildConfigField("String", "MEZON_WEBRTC_ICESERVERS_CREDENTIAL", "\"QuTs4zUEcbylWemXL7MK\"")
        buildConfigField("String", "MEZON_STREAM_WS_URL", "\"https://stn.mezon.ai\"")

        // CDN & Images
        buildConfigField("String", "MEZON_BASE_IMG_URL", "\"https://cdn.mezon.ai\"")
        buildConfigField("String", "MEZON_LOGO_URL", "\"https://cdn.mezon.ai/images/mezon_logo.png\"")
        buildConfigField("String", "MEZON_IMGPROXY_BASE_URL", "\"https://imgproxy.mezon.ai\"")
        buildConfigField("String", "MEZON_IMGPROXY_KEY", "\"K0YUZRIosDOcz5lY6qrgC6UIXmQgWzLjZv7VJ1RAA8c\"")

        // Tenor GIF
        buildConfigField("String", "MEZON_TENOR_KEY", "\"AIzaSyA7PmFsiGws1XF-t6jXsVuF6O2DQLa8BpE\"")
        buildConfigField("String", "MEZON_TENOR_URL_CATEGORIES", "\"https://tenor.googleapis.com/v2/categories?key=\"")
        buildConfigField("String", "MEZON_TENOR_URL_SEARCH", "\"https://tenor.googleapis.com/v2/search?q=\"")
        buildConfigField("String", "MEZON_TENOR_URL_FEATURED", "\"https://tenor.googleapis.com/v2/featured?key=\"")
        buildConfigField("String", "TENOR_API_KEY", "\"AIzaSyAyimkuYQYF_FXVALexPuGQctUWRURdCYQ\"")

        // Sentry
        buildConfigField("String", "MEZON_SENTRY_DSN", "\"https://7aad12a70a52b6598fa5847153a13781@o4509763792404480.ingest.us.sentry.io/4509767257751552\"")

        // Notification
        buildConfigField("String", "MEZON_NOTIFICATION_WS_URL", "\"wss://gotify.mezon.ai\"")

        // Treasury / Blockchain
        buildConfigField("String", "MEZON_TREASURY_URL", "\"https://withdraw-api.nccsoft.vn\"")
        buildConfigField("String", "MEZON_TREASURY_KEY", "\"WTGYB2AJSHUBPAXZULT2Y7LGR4GQ\"")
        buildConfigField("String", "MEZON_TREASURY_URL_NETWORK", "\"https://sepolia.etherscan.io\"")
        buildConfigField("String", "MEZON_CONTRACT_ADDRESS", "\"0x4F17a94dD6E1B2D6241C4D1956C6c7a07ba2Ec50\"")

        // Misc
        buildConfigField("String", "MEZON_ANONYMOUS_USER_ID", "\"1767478432163172999\"")
        buildConfigField("int", "MEZON_MAX_LENGTH_NAME_ALLOWED", "64")
        buildConfigField("String", "MEZON_MMN_API_URL", "\"https://dong.mezon.ai/mmn-api/\"")
        buildConfigField("String", "MEZON_ZK_API_URL", "\"https://dong.mezon.ai/zk-api/\"")
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
            isMinifyEnabled = false
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
    implementation("com.otaliastudios:zoomlayout:1.9.0")

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
