plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.google.services) apply false
}

tasks.register("publishLocalBinaryModules") {
    group = "build setup"
    description = "Publishes stable internal library modules to the project-local Maven repository."
    dependsOn(":core-proto:publishDebugPublicationToMezonLocalRepository")
    dependsOn(":mmn-client-kotlin:publishDebugPublicationToMezonLocalRepository")
}
