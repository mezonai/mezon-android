import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "com.mezon.mmn"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        resValues = false
    }

    publishing {
        singleVariant("debug")
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.bouncycastle:bcprov-jdk18on:${libs.versions.bouncyCastle.get()}")
}

publishing {
    repositories {
        maven {
            name = "mezonLocal"
            url = rootProject.layout.projectDirectory.dir(".gradle/local-maven").asFile.toURI()
        }
    }
    publications {
        register<MavenPublication>("debug") {
            groupId = "com.mezon.local"
            artifactId = "mmn-client-kotlin"
            version = "debug"
        }
    }
}

afterEvaluate {
    publishing.publications.named<MavenPublication>("debug") {
        from(components["debug"])
    }
}
