import com.google.protobuf.gradle.proto
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.protobuf)
    `maven-publish`
}

android {
    namespace = "ai.mezon.proto"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
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

    sourceSets {
        named("main") {
            proto {
                srcDir(rootProject.file("mezon-protocol"))
            }
        }
    }

    publishing {
        singleVariant("debug")
    }
}

// Proto sources come from the git submodule at repo root (see README).
// Using the mezon-protocol root preserves imports like "api/api.proto".

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(libs.protobuf.javalite)
    api(libs.protobuf.kotlin.lite)
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
            artifactId = "core-proto"
            version = "debug"
        }
    }
}

afterEvaluate {
    publishing.publications.named<MavenPublication>("debug") {
        from(components["debug"])
    }
}
