// 이미지 엔진 모듈: 문서·레이어 데이터, 합성, 필터, 브러시 계산, 실행 취소 기록을 맡는다.
// :app을 참조하지 않고, Compose·Activity 같은 UI 코드도 쓰지 않는다. (CLAUDE.md 3장)
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kiln.stratum.engine"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // AI 모델 실행(결정 기록 'AI 제거·AI 선택'). 모델은 APK에 넣어 쓰므로 Play AI 팩 내려받기 부품
    // (Play·gms·WorkManager·guava)은 빼고, androidx lifecycle은 앱이 쓰는 판을 그대로 둔다.
    // 빼면 권한(WAKE_LOCK·ACCESS_NETWORK_STATE·RECEIVE_BOOT_COMPLETED)도 함께 빠진다.
    implementation(libs.litert) {
        exclude(group = "com.google.android.play")
        exclude(group = "com.google.android.gms")
        exclude(group = "com.google.guava")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-guava")
        exclude(group = "androidx.lifecycle")
    }
    testImplementation(libs.junit)
}
