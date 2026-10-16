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
    testImplementation(libs.junit)
}
