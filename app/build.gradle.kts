plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    application
}

application {
    mainClass.set("org.nexadex.app.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":service"))
    implementation(project(":api"))
    implementation(project(":indexer"))

    implementation("org.nexa.sdk:sdk")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    implementation(libs.logback)
    implementation(libs.hikaricp)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.shadowJar {
    archiveBaseName.set("nexa-dex")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.register<JavaExec>("genWallet") {
    description = "Generate a new Nexa wallet (mnemonic + address)"
    mainClass.set("org.nexadex.tools.GenWalletKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("checkBalance") {
    description = "Check LP wallet balance"
    mainClass.set("org.nexadex.tools.CheckBalanceKt")
    classpath = sourceSets["main"].runtimeClasspath
}
