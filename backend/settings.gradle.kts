rootProject.name = "fgc-backend"
// 통합 테스트에서 가상 CGM 에뮬레이터를 같은 JVM 안에서 띄운다 (com.fgc:cgm-emulator 로 치환)
includeBuild("../poc/cgm-emulator")
