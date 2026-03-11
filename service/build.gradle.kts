plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    implementation(project(":data"))

    implementation("org.nexa.sdk:sdk")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
