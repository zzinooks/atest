plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    kotlin("plugin.compose") version "2.0.21" apply false
    // Android Gradle Plugin·google-services 는 app/build.gradle.kts 에서 선언 (SDK 없는 환경에서 :core 만 빌드할 수 있도록)
}
