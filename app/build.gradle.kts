plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.dsh.pocket"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.dsh.pocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.3.5-dev"
        testInstrumentationRunner = "dev.dsh.pocket.ProviderSmokeInstrumentation"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs.getByName("debug") { storeFile = rootProject.file(".cache/debug.keystore") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/pocketAssets"))
}
val bridgeAssets by tasks.registering(Copy::class) {
    from(rootProject.file("bridge")) { into("bridge") }
    from(rootProject.file("termux")) { into("termux") }
    into(layout.buildDirectory.dir("generated/pocketAssets"))
}
tasks.named("preBuild") { dependsOn(bridgeAssets) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    // The android.jar used for unit tests stubs org.json, so decodeText would
    // throw "not mocked" instead of the behaviour under test.
    testImplementation("org.json:json:20240303")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
