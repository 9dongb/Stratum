# 에뮬레이터 확인 방법

화면을 실기기 대신 에뮬레이터로 확인할 때 쓰는 요령이다. 어느 PC에서나 같다.
원래 PC에서 쓰던 방법(2026-10-16)을 옮기고, 다른 PC로 옮겨 오면서 확인한 것을 더했다(2026-10-17).
무엇을 확인할지는 `docs/progress.md`의 '확인 방법'에 있다.

## 에뮬레이터

| 이름 | 기기 | 화면 |
| --- | --- | --- |
| `Medium_Phone_API_37.0` | Medium Phone | 1080×2400, 420dpi |
| `Pixel_Fold` | Pixel Fold | 안쪽 2208×1840, 바깥 1080×2092 |

- 에뮬레이터는 PC마다 따로 만든다. 없으면 Android Studio의 Device Manager에서 만든다.
  - `+` → 기기 선택 → 시스템 이미지는 API 37(Google Play) → 이름 입력
  - 이름의 띄어쓰기는 `_`로 바뀐다. 표의 이름과 같아지게 `Medium Phone API 37.0`, `Pixel Fold`로 입력한다.
  - 16KB 페이지 이미지여도 된다. 앱의 네이티브 라이브러리가 16KB에 맞게 정렬돼 있다(2026-10-17 확인). 네이티브 라이브러리가 든 의존성을 새로 넣으면 다시 확인한다.
- 한 번에 하나만 켠다. 아래 `adb` 명령도 에뮬레이터가 하나만 켜져 있다고 보고 쓴 것이다.
- 켜기: Device Manager의 실행 버튼, 또는 `emulator -avd <이름>`
- 느리거나 켜지지 않으면 가속을 확인한다: `emulator -accel-check`
  - Mac(Apple Silicon)은 따로 설치할 것이 없다. 원래 PC에서는 가속 드라이버를 설치했다.
- 앱 설치: `./gradlew installDebug` (JDK는 `docs/progress.md`의 '기술 주의점' 참고)
- PC 메모리를 많이 쓴다. API 37 에뮬레이터는 설정(2GB)과 달리 4GB로 켜진다(`hardware-qemu.ini`).
  - PC 메모리가 모자라면 부팅이 10분 넘게 걸리고 adb가 `offline`으로 남는다.
  - 켜진 뒤에도 안드로이드가 스스로 다시 시작해, 앱 설치가 "Can't find service: package"로 실패할 수 있다.
  - Docker Desktop처럼 무거운 앱을 끄고 켠다(2026-10-17, 16GB Mac에서 겪음). adb만 `offline`이면 `adb reconnect offline`

## 에뮬레이터 테스트

- 에뮬레이터를 켠 뒤 `./gradlew :app:connectedDebugAndroidTest`
- 앱 설치에 실패해도 `BUILD SUCCESSFUL`로 끝날 수 있다. 결과는 `app/build/outputs/androidTest-results/connected/debug/`의 XML에서 테스트 수와 실패 수로 확인한다(파일 이름에 띄어쓰기가 있다).

## adb·emulator 위치

- Android SDK 폴더 안에 있다. SDK 폴더는 `local.properties`의 `sdk.dir`에 적혀 있다.
  - `adb`: `<SDK 폴더>/platform-tools/adb`
  - `emulator`: `<SDK 폴더>/emulator/emulator`
- PATH에 없으면 전체 경로로 실행한다.

## 앱 실행

- 런처와 같은 방식으로 연다: `adb shell monkey -p com.kiln.stratum -c android.intent.category.LAUNCHER 1`
- `am start -n`으로 띄운 뒤 런처 방식으로 다시 열면 화면이 하나 더 쌓여서 결과가 헷갈린다.

## 화면 캡처·읽기

- 실행 직후 캡처는 까맣게 나올 수 있다. 3~5초 기다린다.
- 캡처: `adb exec-out screencap -p > screen.png`
- 화면 구성 읽기(`uiautomator dump`)는 화면이 바뀌는 중에 실패하고, 그러면 예전 파일이 대신 읽힌다.
  - 매번 예전 파일을 지운다(기기 쪽 기본 위치: `/sdcard/window_dump.xml`).
  - 실패하면 다시 시도한다.

## 접기·펼치기 (Pixel_Fold)

- 접기 `adb emu fold`, 펼치기 `adb emu unfold`
- 지금 상태 보기: `adb shell cmd device_state state`
- 접고 펼 때마다 Activity가 다시 만들어진다.
- 화면이 둘이라 캡처할 화면을 고른다: `adb exec-out screencap -p -d <디스플레이 id> > screen.png`
  - id는 `adb shell dumpsys SurfaceFlinger --display-id`로 찾는다. port 0이 안쪽, port 1이 바깥이다.

## 백그라운드 종료 뒤 복원

1. 편집 화면에서 홈 키를 누른다(`adb shell input keyevent KEYCODE_HOME`).
2. `adb shell am kill com.kiln.stratum`을 실행한다.
3. 앱을 다시 실행해서('앱 실행'의 명령) 편집 화면이 나오는지 본다.

## 가로 화면

- 바꾸기 전에 원래 값을 적어 둔다: `adb shell settings get system accelerometer_rotation`, `adb shell settings get system user_rotation`
- 가로로: `adb shell settings put system accelerometer_rotation 0` 다음에 `adb shell settings put system user_rotation 1`
- 끝나면 적어 둔 값으로 돌린다.

## 앱 언어

- 한국어로: `adb shell cmd locale set-app-locales com.kiln.stratum --locales ko-KR`
- 되돌리기: `--locales` 없이 같은 명령(`adb shell cmd locale set-app-locales com.kiln.stratum`)

## 자주 뜨는 시스템 창

- "Pixel Launcher isn't responding" 같은 창은 PC 메모리가 부족해서 뜬다(앱 문제 아님). Wait로 안 풀리면 Close app을 누른다.
- Pixel_Fold 안쪽 화면에서 처음 실행할 때 위쪽에 뜨는 "Tap handle to access menu…" 말풍선은 시스템 안내다.
