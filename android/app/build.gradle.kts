plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "com.fgc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fgc.app"
        minSdk = 28            // Health Connect 지원 최소
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 에뮬레이터에서 호스트 PC 의 Backend: 10.0.2.2. 실제 배포는 -Pfgc.baseUrl=https://... 로 지정
        buildConfigField("String", "BASE_URL", "\"${project.findProperty("fgc.baseUrl") ?: "http://10.0.2.2:8080"}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

// FCM: google-services.json 이 있을 때만 적용 (저장소에 넣지 않는다)
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha10")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))
    implementation("com.google.firebase:firebase-messaging")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test"))
}
