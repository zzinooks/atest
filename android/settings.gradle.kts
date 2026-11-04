pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "family-glucose-care"

include(":core")
// :app 은 Android SDK 가 있을 때만 포함 (SDK 없는 CI·클라우드 환경에서도 :core 는 빌드·테스트 가능)
val hasSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    (file("local.properties").exists() && file("local.properties").readText().contains("sdk.dir"))
if (hasSdk) include(":app")
