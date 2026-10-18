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
  - 빠른 부팅으로 켠 뒤 앱을 설치할 때마다 안드로이드가 다시 시작하면(`Can't find service: package`가 되풀이됨), 끄고 `emulator -avd <이름> -no-snapshot-load`로 처음부터 켠다. 데이터는 그대로다(2026-10-17 Medium_Phone에서 겪음).
- Pixel_Fold(API 37 Play 이미지)에서 UWB 시스템 서비스(`vendor.uwb_hal`)가 5초마다 죽고 다시 켜지면 화면 테스트 하나가 1분 가까이 걸릴 만큼 느려진다(2026-10-19 겪음).
  - 확인: `adb -s emulator-5554 logcat -d -b crash`에 `android.hardware.uwb-service`가 되풀이된다.
  - 해결: 끄고 `emulator -avd Pixel_Fold -no-boot-anim -feature -Uwb`로 켠다(UWB를 쓰지 않는 앱이라 꺼도 된다).
  - PC 메모리가 모자라 스왑을 많이 쓰면(`sysctl vm.swapusage`) 그래도 느리다. 펼친 상태보다 접은 상태(커버 화면)가 두 배쯤 빠르므로, 중간 확인은 접어서 돌린다. 마지막 전체 테스트도 펼친 상태로 3시간이 넘으면 접은 상태로 돌리고, 접은 상태에서만 실패하는 5개와 배치(힌지) 테스트만 펼쳐서 따로 돌린다(progress.md '기술 주의점', 2026-10-19).

## 에뮬레이터 테스트

- 에뮬레이터를 켠 뒤 `./gradlew :app:connectedDebugAndroidTest`
- 앱 설치에 실패해도 `BUILD SUCCESSFUL`로 끝날 수 있다. 결과는 `app/build/outputs/androidTest-results/connected/debug/`의 XML에서 테스트 수와 실패 수로 확인한다(파일 이름에 띄어쓰기가 있다).
- 테스트 클래스 하나만: `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=<패키지.클래스>"`
  - 쉼표로 여러 클래스를 주면 첫 클래스만 돈다(2026-10-17 확인). 하나씩 돌린다. 메서드 하나는 `<패키지.클래스>#<메서드>`
- 끝나면 테스트 앱을 지운다. `adb shell am instrument`로 직접 돌리려면 먼저 `./gradlew installDebug installDebugAndroidTest`
- 폰이 연결돼 있으면 gradle의 `installDebug`·`connectedDebugAndroidTest`는 폰에도 설치한다. 에뮬레이터에서만 돌리는 방법(2026-10-18):
  1. `./gradlew assembleDebug assembleDebugAndroidTest`로 APK만 만든다.
  2. `adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk`, 같은 명령으로 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  3. `adb -s emulator-5554 shell am instrument -w -r -e class <패키지.클래스> com.kiln.stratum.test/androidx.test.runner.AndroidJUnitRunner`
  - 결과는 마지막의 `OK (N tests)`로 본다. 실패하면 `FAILURES!!!`와 `Tests run`이 나온다.
  - 여기서는 쉼표로 여러 클래스를 주면 준 순서대로 모두 돈다. `<패키지.클래스>#<메서드>`도 섞어 쓸 수 있다.
  - 끝나면 `adb -s emulator-5554 uninstall com.kiln.stratum.test`
- 테스트 전체는 Pixel_Fold를 펼친 상태(`adb emu unfold`)에서 돌린다(너무 느리면 위 UWB 항목의 마지막 줄처럼 나눠 돌린다). 접은 상태(커버)에서는 화면보다 큰 강제 크기를 쓰는 메인 세로(가로 힌지) 테스트 4개와 체크 무늬 색 테스트 1개가 실패한다(코드 문제가 아님, 2026-10-18 확인).
- 화면 테스트(Compose)는 하나에 25~40초 걸릴 수 있다. 멈춘 것이 아니다.
  - 이 PC(2026-10-17)의 에뮬레이터는 그래픽을 GPU가 아니라 CPU로 그려서(켤 때 로그의 `lavapipe`·`swangle`) 시스템 화면까지 느리다.

## 테스트 사진 넣기

- PC의 사진을 에뮬레이터 사진 폴더에 넣는다: `adb push 사진.jpg /sdcard/Pictures/StratumTest/`
- 넣은 뒤 미디어 검사를 해야 사진 선택기에 보인다: `adb shell content call --uri content://media --method scan_volume --arg external_primary`
- 사진 선택기(API 37)는 사진을 누르면 체크만 되고, 아래 "Done"을 눌러야 앱으로 돌아온다.
- 선택기 위쪽의 안내 배너가 나타났다 사라지며 사진 칸 위치가 바뀐다. 누르기 전에 `uiautomator dump`로 위치를 다시 읽는다.
- 테스트용 큰 사진(격자 무늬 12MP·50MP·200MP, EXIF 회전, 투명 PNG, 깨진 파일)은 PC의 파이썬(PIL)으로 만들어 쓰고 저장소에는 넣지 않는다.

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
- 화면 테스트 안에서 찍기(예: 두 손가락으로 확대한 화면): `InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()`을 앱 폴더(`getExternalFilesDir`)에 PNG로 저장하고 `adb pull`로 받는다(2026-10-17).
  - 테스트가 끝나면 앱이 지워지면서 앱 폴더도 지워진다. `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`를 붙여 돌리고, 받은 뒤 `adb uninstall com.kiln.stratum.test`, `adb uninstall com.kiln.stratum`으로 지운다.
  - 두 손가락 조작 바로 뒤에 찍으면 조작 전 화면이 찍힌다(그래픽을 CPU로 그려 느림). 1초쯤 기다린 뒤 찍는다.
- 화면 구성 읽기(`uiautomator dump`)는 화면이 바뀌는 중에 실패하고, 그러면 예전 파일이 대신 읽힌다.
- 글자 칸을 누른 뒤 키보드가 다 열리기 전에 `adb shell input text`를 보내면 첫 글자가 두 번 들어갈 수 있다(Sunset → SSunset, 앱 문제 아님, 2026-10-19). 칸을 누르고 3~4초 기다린 뒤 보낸다.
  - 매번 예전 파일을 지운다(기기 쪽 기본 위치: `/sdcard/window_dump.xml`).
  - 실패하면 다시 시도한다.

## 접기·펼치기 (Pixel_Fold)

- 접기 `adb emu fold`, 펼치기 `adb emu unfold`
- 지금 상태 보기: `adb shell cmd device_state state`
- 접고 펼 때마다 Activity가 다시 만들어진다.
- 접고 펴면 회전 값(`accelerometer_rotation`·`user_rotation`)이 바뀐다. 접은 상태와 편 상태가 값을 따로 기억하는 것으로 보인다(2026-10-17).
  - 돌린 방향을 다시 보려면 다시 돌린다.
  - 끝나면 펼친 상태에서 원래 값으로 되돌렸는지 다시 확인한다.
- 앱이 받은 힌지 정보는 `adb logcat -s WindowHinges`로 본다(2026-10-17 확인).
  - 안쪽 화면 기본 방향: `Rect(1104, 0 - 1104, 1840) VERTICAL FLAT`(두께 0인 세로선)
  - 안쪽 화면을 돌린 방향: `Rect(0, 1104 - 1840, 1104) HORIZONTAL FLAT`
  - 접은 상태(바깥 화면): 빈 목록
- 화면이 둘이라 캡처할 화면을 고른다: `adb exec-out screencap -p -d <디스플레이 id> > screen.png`
  - id는 `adb shell dumpsys SurfaceFlinger --display-id`로 찾는다. port 0이 안쪽, port 1이 바깥이다.
- 화면 크기에 맞춰 축소본을 다시 그렸는지는 `adb logcat -s EditorViewModel`의 "Preview level" 줄로 본다.

## 백그라운드 종료 뒤 복원

1. 편집 화면에서 홈 키를 누른다(`adb shell input keyevent KEYCODE_HOME`).
2. 다른 앱을 하나 열었다가 홈으로 간다(예: `adb shell am start -a android.settings.SETTINGS`). 방금 나간 앱은 `am kill`이 종료하지 않는다(2026-10-17 확인).
3. `adb shell am kill com.kiln.stratum`을 실행하고, `adb shell pidof com.kiln.stratum`이 비어 있는지 본다.
4. 앱을 다시 실행해서('앱 실행'의 명령) 편집 화면이 나오는지 본다.

## 가로 화면

- 바꾸기 전에 원래 값을 적어 둔다: `adb shell settings get system accelerometer_rotation`, `adb shell settings get system user_rotation`
- 가로로: `adb shell settings put system accelerometer_rotation 0` 다음에 `adb shell settings put system user_rotation 1`
- 끝나면 적어 둔 값으로 돌린다.
- `adb emu rotate`는 누를 때마다 90°씩 돌리고, 접은 화면(커버)에도 그 방향이 남는다(두 번이면 커버가 거꾸로 서서 카메라 구멍이 아래로 간다). 네 번 돌려 원래대로 하거나 위 방법을 쓴다(2026-10-19).
  - 명령 결과를 변수에 담아 되돌릴 때는 끝의 `\r`을 지운다(`tr -d '\r'`). 안 지우면 값이 0으로 들어간다(2026-10-17 겪음).

## 앱 언어

- 한국어로: `adb shell cmd locale set-app-locales com.kiln.stratum --locales ko-KR`
- 되돌리기: `--locales` 없이 같은 명령(`adb shell cmd locale set-app-locales com.kiln.stratum`)

## 자주 뜨는 시스템 창

- "Pixel Launcher isn't responding" 같은 창은 PC 메모리가 부족해서 뜬다(앱 문제 아님). Wait로 안 풀리면 Close app을 누른다.
  - "Process system isn't responding"·"System UI isn't responding"도 같다. 먼저 Wait를 누른다(Close app은 안드로이드를 다시 시작시킨다).
- Pixel_Fold 안쪽 화면에서 처음 실행할 때 위쪽에 뜨는 "Tap handle to access menu…" 말풍선은 시스템 안내다.

## 실기기(폴드8)로 확인

에뮬레이터 대신 사용자의 폴드8에 설치해 볼 때의 요령이다(2026-10-17 처음 해 봄).

- 폰 준비(처음 한 번):
  - 설정 → 휴대전화 정보 → 소프트웨어 정보 → '빌드 번호'를 7번 누르면 '개발자 옵션'이 생긴다.
  - 개발자 옵션 → 'USB 디버깅'을 켠다.
  - 설정 → 보안 및 개인정보 보호 → '자동 차단'이 켜져 있으면 USB 디버깅이 막힌다. 테스트하는 동안만 끈다.
- USB-C 데이터 케이블로 Mac에 연결하고, 폰에 뜨는 'USB 디버깅 허용'에서 '이 컴퓨터에서 항상 허용'을 고른다.
  - `adb devices -l`에 기기가 `device`로 보이면 된다. 케이블이 없으면 Android Studio Device Manager의 'Pair devices using Wi-Fi'(무선 디버깅)를 쓴다.
- 설치: Android Studio에서 기기를 고르고 실행(▶), 또는 `./gradlew installDebug`(JDK는 `docs/progress.md`의 '기술 주의점')
- 접히는 선 정보: 앱을 연 채로 `adb logcat -s WindowHinges`
  - 폴드8에서 읽은 값(2026-10-17):
    - 접은 상태(커버, 1248×1972): 빈 목록
    - 펼친 기본 방향(2448×1848): `Rect(1224, 0 - 1224, 1848) VERTICAL FLAT`
    - 펼쳐서 돌린 방향(1848×2448): `Rect(0, 1224 - 1848, 1224) HORIZONTAL FLAT`
  - 펼치는 순간 한 번, 아직 커버 크기인 창 기준으로 `Rect(624, 0 - 624, 1972)`가 왔다가 약 0.16초 뒤 바뀐다.
