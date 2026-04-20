import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "ai.mezon.proto"
    compileSdk = 34

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

    sourceSets {
        getByName("main") {
            // Use the protobuf DSL accessor to set proto source roots.
            proto {
                srcDirs(
                    "$rootDir/mezon-protocol",
                    "$rootDir/mezon-protocol/api",
                    "$rootDir/mezon-protocol/rtapi",
                    "$rootDir/mezon-protocol/proto"
                )
            }
        }
        getByName("debug") {
            java.srcDir("$buildDir/generated/source/proto/debug/java")
            kotlin.srcDir("$buildDir/generated/source/proto/debug/kotlin")
        }
        getByName("release") {
            java.srcDir("$buildDir/generated/source/proto/release/java")
            kotlin.srcDir("$buildDir/generated/source/proto/release/kotlin")
        }
    }
}

// Proto sources are read directly from mezon-protocol so imports like "api/api.proto" resolve
// even on Windows where symlinks are not available.

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
