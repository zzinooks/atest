plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Android(app)과 같은 바이트코드 수준(17)으로 컴파일. 설치된 JDK 버전과 무관
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    // 실제 Backend 대상 계약 테스트: FGC_LIVE_BASE_URL 이 있을 때만 실행
    System.getenv("FGC_LIVE_BASE_URL")?.let { environment("FGC_LIVE_BASE_URL", it) }
}
