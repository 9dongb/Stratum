# 질감 향상 계획

상태: 승인됨(2026-10-23). 진행 상황은 `docs/progress.md`의 '진행 중', 단계마다 한 일은 `docs/history.md`에 있다.

## Context

- **요청(사용자, 그대로)**: "질감 향상이라는 이름을 따로 붙여서 선택적으로 적용시키는건 어떤가요?(도구에서 옵션으로 체크를 하게하든,  AI 제거 후 누를 수 있게 하든,,가장 적합한 방법으로) 작업은 Stratum AI 기능 계획 세션이 끝나면 진행하세요."
  - 덧붙인 요청(계획 중에): "우선 지금 기준 앱 apk 만들어서 구글 드라이브에 올려줘"
  - 의도: 말하지 않음.
- **왜 필요한가**: 큰 사진에서 큰 물체를 지우면, 512px로 채운 그림을 몇 배 키워 넣어서 채운 곳이 뿌옇다. 지금은 고운 잡음만 더한다. 둘레 원본에서 고운 결(아스팔트·흙·풀 무늬)을 찾아 덧입힌다.
- **맥 시험**(실제 사진 5곳, 앱과 같은 순서로 채움, 비교 그림은 따로 보냄)
  - 고운 결의 세기(정답 / 지금 / 질감 향상): 길 9.48 / 1.84 / 6.98, 보도 9.62 / 3.78 / 6.90, 벽면 10.07 / 5.61 / 7.82, 흙바닥 22.52 / 9.81 / 19.33, 땅 22.52 / 10.27 / 12.30
  - 큰 모양(1/8로 줄여 본 오차)은 바뀌지 않는다.
  - 폰에 맞춘 빠른 짝 찾기(거친 4×4로 후보 4개를 고른 뒤 8×8로 하나)는 모두 비교했을 때와 84~99% 같은 조각을 골랐고, 차이는 2% 안이다.

## 정한 것 (사용자가 '가장 적합한 방법으로' 맡김, Claude 판단은 이 계획 승인으로 확정)

- **버튼**: AI로 채운 바로 뒤에 누르는 [질감 향상]. 따로 실행 취소 한 단계라, 실행 취소 한 번이면 전의 매끈한 채우기로 돌아간다.
  - 자리
    - 리터칭 'AI 제거': 커버 막대의 [지우기] 옆, 펼친 화면 띠의 지우기 칸 옆
    - 선택 도구 동작 줄: AI 채우기 옆(커버 막대, 펼친 화면 아래 동작 줄)
  - 아이콘은 Material Icons `texture`(Android Studio 내장 파일, `ic_texture.xml`)다.
  - 이름은 '질감 향상'(영어 'Texture'), 화면 읽기는 'AI로 채운 곳에 둘레의 고운 결 더하기'다.
  - 켜지는 때: 지금 문서가 가장 마지막 AI 채우기(AI 제거 칠하기·톡 지우기, AI 채우기)의 결과 그대로일 때만이다.
    - 다른 편집을 하면 꺼진다. 실행 취소로 그 결과에 돌아오면 다시 켜진다. 누른 뒤에는 꺼진다.
  - 더할 결이 없을 때(창이 원본의 1.5배 안으로 채워진 작은 곳)는 누르면 알림만 띄우고 기록하지 않는다.
    - 알림: "이미 사진만큼 선명하게 채워서 더할 결이 없어요. 큰 사진에서 넓게 지운 곳에 써 보세요."
    - 이유: 버튼을 꺼 두면 왜 꺼졌는지 알기 어렵다.
  - AI 제거 안내 글에 한 문장 더한다: "채운 곳이 흐리면 질감 향상을 눌러 둘레의 결을 옮겨요."
- **계산**
  - 같은 채우기를 채우기 전 픽셀에서 다시 하되, 잡음 대신 결을 붙인다. 창·모델·색 맞추기는 같고, 모델은 폴드8 GPU에서 창마다 약 30ms다.
    - 이유: 채운 결과 위에 붙이면 잡음과 결이 겹치고, 경계 띠가 두 번 섞인다.
  - 배율 k(모델 1px이 덮는 문서 px)가 1.5 이상인 창에만 결을 붙인다. 그 밖의 창은 지금처럼 잡음을 더한다.
  - 짝 찾기(모델 크기 512², Kotlin)
    - 채운 곳에 닿는 8칸 조각(4칸 간격)마다 짝을 찾는다.
    - 후보: 구멍에서 96칸 안, 다 알고 불투명한 조각(4칸 간격, 고르게 512개까지)
    - 고르기: 채운 색과 가장 비슷한 후보. 거친 4×4×3으로 4개를 고른 뒤 8×8×3으로 하나를 고른다.
  - 붙이기(원본 해상도, GPU)
    - 결 = 원본 − 모델 크기에서 바이큐빅으로 키운 원본
    - 조각마다 한 변 8k px의 cos² 창으로 겹쳐 더하고, 분산이 줄지 않게 √Σw²로 나눈다.
    - '키운 채운 색 + 결'을 지금의 쓰기 몫 ∩ 선택 영역만큼 넣는다. 몫이 0인 픽셀은 그대로다.
  - 가져올 조각 판은 32MB 안이다(배율이 아주 크면 후보 수를 줄인다).
  - 게인맵은 채우기 결과 그대로 둔다(매끈한 그림이라 결이 없다).
  - 한 조각도 붙이지 못하면(둘레가 투명 등) 더할 결이 없는 것과 같다.
- **하지 않는 것**
  - 여러 번 지운 뒤 한꺼번에 향상(가장 마지막 채우기만 된다)
  - 앱을 다시 연 뒤 향상(실행 취소 기록처럼 남기지 않는다)

## 설계

### 엔진(`:engine`)

- 새 `engine/inpaint/InpaintTexture.kt`(순수 Kotlin, JVM 테스트)
  - 상수: `MIN_SCALE` 1.5, `CELL` 8, `STRIDE` 4, `NEAR` 96, `KNOWN_GAP` 2, `MAX_CANDIDATES` 512, `TOP` 4, `ATLAS_BYTES` 32MiB, `BLOCK_MARGIN` 2
  - 함수: `candidates`, `targets`, `match`(두 단계), `maxCandidates(k)`
  - 결과 `TextureMatch`: 칸마다 쓴 조각 번호와 쓴 조각들의 자리
- 새 `engine/platform/TextureTransfer.kt`
  - 조각 판: 8888, 쓴 조각만 담는다. 타일에서 `CanvasTileComposer.bitmapOf`·`CopyPaint`로 베낀다.
  - 짝 표: 8888, 칸마다 가져올 조각의 자리와 번호
  - AGSL `TEXTURED_BICUBIC`
    - AGSL은 배열을 상수·반복 번호로만 읽을 수 있어서, 짝을 작은 그림으로 넘긴다.
    - F16은 2048보다 큰 정수가 정확하지 않아서, 8888 채널에 나눠 담는다.
- `ImageResizer.BICUBIC_SAMPLE`: `bicubicAt(source)`를 꺼내고, `bicubic(position)`은 그것을 부른다(동작 그대로).
- `Inpainter`
  - 계획 부분(구멍·덩어리·창)을 함수로 꺼낸다.
  - `paint(…, texture = false)`
    - texture이고 k ≥ 1.5인 창: 짝 찾기 → 판 만들기 → `composite`/`upscale`이 결 셰이더를 쓴다(잡음 0).
    - 후보가 없으면 잡음으로 돌아간다.
  - 결을 붙인 창이 있었는지 알린다.
- 새 명령 `EnhanceTexture(layerId, area, model, original: Raster)`(`platform/EnhanceTexture.kt`)
  - `original`은 채우기 전 레이어 픽셀이다. 여기에 `Inpainter.paint(texture = true)`를 하고, 레이어는 `withPixels(결과, 지금 게인맵)`이다.
  - 더할 창이 없거나 결을 붙이지 못하면 `NothingToEnhanceException`을 던진다.
  - `estimateNewBytes`는 `InpaintRegion`과 같다. `estimateWorkBytes` = `WORK_BYTES` + `TEXTURE_WORK_BYTES`(64MiB) + 모델 불러오기.

### 앱(`:app`)

- `EditorViewModel`
  - `AiFill(command: InpaintRegion, original: Raster, result: Document)`를 세 곳의 `onCommitted`에서 남긴다: 칠하기(`commitNextStroke`), 톡 지우기(`removePickedObject`), AI 채우기(`fillSelection`).
    - `original`은 `runEdit` 바로 전 `history.document`의 그 레이어 픽셀이다.
  - `textureFill: StateFlow<Long?>`: 질감 향상을 할 수 있는 revision이다. `showPreview`의 문서가 바뀌는 블록에서 `보이는 문서 === AiFill.result`로 정한다(실행 취소·다시 실행도 맞음).
  - `enhanceTexture()` → `runEdit(EnhanceTexture(…))`. `NothingToEnhanceException`은 새 `EditorMessage.TextureNothing`(알림 막대)으로 알린다.
  - `release()`·`close()`에서 지운다. `releaseAiModels`에서는 지우지 않는다(앱이 뒤로 갈 때도 불린다).
- 화면
  - `RetouchActions.enhanceTexture`·`RetouchControls.textureReady`, `SelectionActions.enhanceTexture`·`SelectionControls.textureReady`
  - `EditScreen`: `removePick`처럼 revision이 같은지 보고 켠다. 알림 막대 문구도 여기서 띄운다.
  - 버튼: `CoverBars.retouchBar`의 `BarButton`, `ToolStrips.retouchStrip`의 `IconCell`, `SelectionActionBar.SelectionActionButtons`의 버튼
  - `EditScreenTags`에 둘을 더한다: `RETOUCH_ENHANCE_TEXTURE`, `SELECTION_ENHANCE_TEXTURE`
  - 문구(두 언어): `texture_enhance`, `texture_enhance_description`, `edit_texture_nothing`, `retouch_hint_remove`에 한 문장

## 단계 (단계마다 빌드·테스트 통과 후 커밋)

0. **APK 먼저(사용자 요청 "우선")**: 지금 HEAD로 디버그 APK를 만들어 구글 드라이브에 올리고, 올라간 것을 확인한다.
   - 작업 폴더가 아니라 HEAD를 따로 꺼낸 폴더에서 빌드한다(다른 세션의 작업 중 파일이 섞이지 않게). 커밋은 없다.
   - 결과: 같은 코드(145cd65)의 APK를 다른 세션이 이미 올려 두어(`Stratum-debug-20261023-145cd65.apk`, 13:16 올라감 확인, 그 뒤 바뀐 앱 파일 없음) 새로 만들지 않고 알렸다.
1. **기록**: `docs/texture-enhance-plan.md`(이 계획), `docs/progress.md`('진행 중'·결정 기록 'AI 제거·AI 선택'의 '질감 향상'), `docs/history.md`
2. **짝 찾기**: `InpaintTexture` + JVM 테스트 `InpaintTextureTest`
   - 후보는 다 알고·불투명·문서 안·96칸 안이고, 고르게 줄인다.
   - 구멍 픽셀마다 덮는 조각 넷이 모두 있다.
   - 두 단계 짝 찾기가 분명한 짝을 찾는다.
   - 배율이 크면 후보 수가 줄어 판이 한도 안이다.
3. **결 붙이기·명령**: `TextureTransfer`, `Inpainter`, `ImageResizer`, `EnhanceTexture` + 에뮬레이터 테스트 `EnhanceTextureTest`
   - 1px 줄무늬 바닥의 큰 구멍: 채운 곳에 줄무늬가 생긴다(가짜 모델은 평균색으로 채움). 몫 밖 픽셀은 비트 그대로다.
   - 선택 영역 밖은 그대로다.
   - 작은 구멍은 `NothingToEnhanceException`이다.
   - 게인맵은 채우기 결과 그대로다.
   - 두 번 해도 같다.
   - 기존 `InpaintRegionTest`·`HdrTest`가 그대로 통과한다.
4. **화면·ViewModel**
   - 테스트: `EditorRemoveTest`, `RetouchToolTest`, `SelectionToolTest`, `OptionStripTest`
     - 채운 뒤 켜짐 → 누르면 한 단계 → 실행 취소하면 다시 켜짐 → 다른 편집이면 꺼짐
     - 작은 곳은 알림만 뜨고 단계가 늘지 않는다.
     - 펼친 화면 동작 줄이 8칸이어도 한 줄이다.
   - 에뮬레이터에서 진짜 모델로 직접 써 본다(커버·펼친 화면).
5. **마무리**: 스스로 검토(3장 모듈 규칙 등), 기록, 최종 보고(쉬운 말, 폴드8에서 볼 것)

## 함께 쓰는 폴더

- 'Stratum AI 기능 계획' 세션이 커버 막대 작업으로 `EditScreen.kt`·`CoverBars.kt`·strings.xml·progress.md·history.md 등을 고치는 중이다.
- 엔진 단계(2·3)는 겹치지 않는다.
- 4단계는 시작하기 전에 그 세션에 알린다. 겹치는 파일은 내 변경만 골라 커밋한다(`git diff`에서 내 부분만 `git apply --cached`).
- 에뮬레이터는 쓰기 전에 알린다(지금은 그 세션이 쓰는 중).

## 확인 방법

- 단계마다 `./gradlew assembleDebug test`를 돌린다.
- 에뮬레이터 테스트는 클래스마다 따로 돌리고, 테스트 수를 결과 XML로 확인한다.
- **폴드8(사용자 확인)**
  1. 큰 사진에서 사람·자동차처럼 큰 것을 AI 제거로 지운 뒤 [질감 향상]을 누르면, 채운 곳에 둘레의 고운 결이 생기나요? 실행 취소 한 번이면 매끈한 채우기로 돌아오나요?
  2. 작은 것을 지운 뒤 누르면 "더할 결이 없어요" 알림만 뜨나요?
  3. 선택 도구의 AI 채우기 뒤에도 켜지나요? 다른 편집을 하면 꺼지고, 실행 취소로 돌아가면 다시 켜지나요?
  4. 하늘·벽처럼 고른 곳에서 같은 무늬가 바둑판처럼 되풀이되거나 조각 경계가 보이지 않나요?
  5. 누르고 끝날 때까지 얼마나 걸리나요?

## 위험과 대응

| 위험 | 대응 |
|---|---|
| 고른 곳에서 같은 조각이 되풀이돼 무늬가 보임 | 폴드8에서 보고, 보이면 같은 조각을 되풀이할 때 벌점을 준다 |
| 시간 | 창 하나의 짝 찾기는 약 0.1~0.3초(Kotlin)로 본다. GPU 붙이기는 지금 키우기보다 약 5배 더 읽는다. 폴드8에서 잰다 |
| 메모리 | 조각 판은 32MB 한도이고, `estimateWorkBytes`로 알려 실행 취소 기록 정리(`makeRoom`)에 들어간다 |
| 다른 세션과 같은 파일 | 엔진부터 하고, 화면 단계 전에 알리고, 내 부분만 커밋한다 |
