# AI 제거·AI 선택 구현 계획

사용자가 2026-10-20에 승인한 계획이다. 구현하면서 바뀐 것은 `docs/progress.md`(진행 중·결정 기록)에 적고, 이 문서는 승인 때 그대로 둔다.

## Context

- **사용자 요청 1(원문)**: "AI 개체(객체) 제거 기능을 만들고 싶습니다. 보통 온디바이스 모델을 사용하나요? 실무(어도비,삼성 등)에서는 어떤 방식을 통해 어떤 모델들로 구현하는 지 조사해서 구현 계획 세워주세요"
- **사용자 요청 2(원문, 계획을 보고 더함)**:
  1. "탭해서 영역 잡기 기능도 연계하고 싶습니다. MediaPipe이나 MobileSAM 계열을 사용하면 될 것 같은데 적절한가요? 이 기능은 개체 제거 시 개체 선택에 함께 사용되었으면 합니다."
  2. "추가로 AI 개체추출 기능을 추가해서 이에 사용하면 좋을 것 같아요."
  3. "그리고 선택 도구에서 영역 잡을 때도 AI 영역 선택 처럼 기능이 있었으면 좋겠습니다."
  - 덧붙인 말: "2번과 3번이 겹친다고 생각되면 나에게 말해주세요. 하나만 남기든 결정하겠습니다."
- **의도**: 말하지 않음.
- **왜 필요한가**
  - 지금 스팟 복구는 둘레의 한 자리를 통째로 베끼는 '근접 일치'라서, 둘레에 같은 무늬가 없으면 새 내용을 만들지 못한다. 결정 기록 '리터칭'에도 "포토샵 기본인 '내용 인식'은 큰 탐색(또는 AI)이라 3단계 '개체 제거'와 함께 본다"고 적혀 있다.
  - AI 인페인팅(구멍을 둘레에 어울리게 채우는 계산)으로 이 빈자리를 채운다.
  - 탭 한 번으로 물체 모양을 찾는 'AI 선택'은 CLAUDE.md 8장 3단계 '피사체 선택'에 해당한다. 같은 결과로 개체 제거와 추출에도 쓴다.
- **조사 결과**(`docs/ai-remove-research.md`)
  - 실무는 두 층이다. 기본 지우개는 폰 안에서, 생성형 채우기는 서버에서 돈다.
  - 회사들은 모델을 공개하지 않는다. 그래서 공개 연구 모델을 쓴다.

## 사용자 결정 (2026-10-20)

- **1차(모두 추천 수용)**
  1. 실행 위치: 폰 안에서만. 인터넷 권한을 넣지 않고, 설정의 "Stratum은 인터넷을 쓰지 않아요" 약속을 지킨다.
  2. 채우기 모델: MI-GAN 512 Places2. LiteRT fp16 `migan_fp16.tflite`(16,312,640바이트)이고, 코드·가중치·변환본 모두 MIT다(Claude가 원본 파일로 확인).
  3. 새 라이브러리: LiteRT를 추가한다. 모델은 APK에 넣는다. 앱이 약 20MB 커지는 것(arm64 내려받기 기준)을 승인했다.
  4. 도구 자리: 리터칭의 넷째 하위 도구 'AI 제거' 브러시(칠하고 손을 떼면 지움). 그다음 선택 도구에 'AI 채우기'를 둔다.
- **2차**
  - LiteRT 판
    - 사용자 질문: "확실히 미리 되는지 알 방법 없나요?"
    - 답: 미리 알 수 있는 것은 '안 될 가능성이 높다'까지다. 확실히 아는 방법은 실제 빌드뿐이다.
    - 1단계 첫 작업으로 저장소 밖 임시 복사본에서 2.3.0과 2.2.0을 각각 빌드해 본다. 2.3.0이 되면 2.3.0(`litert`+`litert-gpu`), 안 되면 2.2.0(`litert` 하나, 쓰는 API 같음)을 쓴다. Kotlin은 올리지 않는다(제안, 이 계획 승인으로 확정).
  - 딸린 권한: 빼고 시험하기(추천 수용).
    - 정정: 앞서 "LiteRT는 권한을 붙이지 않는다"고 한 것은 틀렸다.
    - `litert-api`가 `FOREGROUND_SERVICE`·`FOREGROUND_SERVICE_DATA_SYNC`를 선언하고, Play AI 팩용 부품이 딸려 온다. INTERNET은 없다.
  - 모델 파일: git 저장소에 넣는다(추천 수용). MIT 고지를 함께 넣는다. 내려받기는 그때 이름·출처·크기를 말하고 허락을 받는다.
  - 감싸서 지우기: 고리처럼 둘러 칠하면 안쪽까지 지운다(추천 수용).
- **3차(AI 선택)**
  - 1번 질문의 답
    - MediaPipe 라이브러리는 쓰지 않는다. 구글 기록 전송 부품이 딸려 와 인터넷 권한이 붙는다. 모델 파일만 돌리는 길은 지금 판의 입력 형식이 공개돼 있지 않아 위험하다.
    - SAM 계열의 작은 모델은 맞다. 사진을 한 번 읽어 두고, 탭마다 빠르게 모양을 찾는다.
  - 2번·3번: 겹친다고 알렸고, 사용자가 "3번만 남기기 (추천)"를 골랐다.
    - 선택 도구에 'AI 선택'을 둔다.
    - 추출은 'AI 선택 → 새 레이어로 복사'(지금 있는 버튼)로 한다.
    - 제거는 'AI 선택 → AI 채우기'로 한다.
  - AI 선택 모델: 사용자가 "폴드8에서 둘 다 재고 결정"을 골랐다(추천은 SAM 2.1 Tiny였다).
    - SAM 2.1 Tiny(약 97MB, Apache-2.0, LiteRT 변환본 있음)와 MobileSAM 계열(약 20MB, 직접 변환)을 폴드8에서 잰다.
    - 크기·속도·정확도를 보여 주고, 사용자가 고른다.
  - AI 제거 도구에서 탭: "탭하면 표시 후 지우기 (추천)". 물체를 톡 누르면 찾은 모양을 반투명으로 보여 주고, [지우기]를 누르면 지운다. 끌어서 칠하면 지금처럼 손을 떼면 지운다.
  - 자동 피사체 버튼: 두지 않는다("탭으로 고르기만 (추천)"). 모델은 하나다.
- **Claude 판단(이 계획 승인으로 확정)**
  - 아이콘: AI 제거는 Material Icons `auto_awesome`(`auto_fix_high`는 자동 선택이 이미 씀), AI 선택은 `ads_click`.
  - AI 제거 옵션은 크기만 둔다(경도·불투명도 없음).
  - P3 문서는 값을 그대로 넣는다.
  - AI 선택은 보이는 레이어를 합친 그림에서 찾는다(스포이드와 같은 규칙).
  - AI 계산은 전용 스레드 하나에서만 한다.

## 진행 방식

- 0단계(기록)는 승인한 날 마쳤다(커밋 "AI 제거·AI 선택 조사·계획과 사용자 결정을 기록함").
- 구현(1단계부터)은 사용자가 "시작"이라고 할 때 한다. 단계마다 빌드·테스트(`./gradlew assembleDebug`, `./gradlew test`)를 통과하면 커밋한다(CLAUDE.md 9장).

## 설계 한눈에

- **엔진(`:engine`)**
  - 순수 Kotlin(JVM 테스트)
    - `engine/inpaint/`: `InpaintModel`, `InpaintArea`(칠한 자국 / 선택 영역 / 물체 마스크), `InpaintMask`, `InpaintPlan`, `InpaintTensor`
    - `engine/segment/`: `SegmentModel`, 탭 좌표 바꾸기, 마스크 다듬기
  - 안드로이드 부분(`engine/platform/`)
    - 명령: `InpaintRegion`, `SelectObject`
    - 처리 순서: `Inpainter`
    - LiteRT 구현: `LiteRtInpaintModel`, 고른 분할 모델 하나
    - 사진 특징을 기억하는 `ObjectSegmenter`
    - `GainmapEdits.fill`
- **앱(`:app`)**: `AssetManager`·`cacheDir`만 넘겨 모델 서비스를 만들고, 명령에 담아 보낸다. 픽셀 계산은 하지 않는다(CLAUDE.md 3장).
- **원칙**
  - 채우기 모델은 512px, 분할 모델은 1024px에서 돌린다.
  - 원본 해상도에서는 GPU와 안드로이드 그리기만 쓴다.
  - Kotlin 반복은 1024px 이하 그림에서만 한다(선례: `HoleFill` 512, `SpotSearch` 1024, 자동 선택 2048).
  - 마스크 밖 픽셀은 한 비트도 바꾸지 않는다.
  - 지우기·선택 한 번이 실행 취소 한 단계다.

## 엔진 설계 ① AI 제거

- **모델(`InpaintModel` / `LiteRtInpaintModel`)**
  - 입력은 `concat(mask−0.5, rgb·mask)`(1×4×512², rgb = pixel/127.5−1, mask는 1이 남김)이고, 출력은 [−1,1] RGB다.
  - 파일은 `CompiledModel.create(AssetManager, "models/migan_fp16.tflite", …)`로 연다. ByteBuffer를 받는 판은 없다.
  - 전용 스레드 `stratum-ai`에서만 만들고, 쓰고, 닫는다.
  - 처음 쓸 때 불러온다. GPU로 시험해 NaN이면 CPU로 다시 하고, 둘 다 안 되면 `InpaintUnavailableException`을 던진다.
  - 내려놓는 때: 편집 화면을 닫을 때, `onCleared()`, `onTrimMemory(UI_HIDDEN)`.
  - 명령에는 오래 사는 서비스 참조만 담는다. 실행 취소 기록은 다시 실행할 때 저장된 문서를 쓰므로 문제없다.
- **한 번 지울 때 (`InpaintRegion(layerId, area, model)` → `Inpainter`)**
  1. 마스크: 칠한 자국·선택 영역·물체 마스크를 긴 변 1024px 이하에 그린다.
     - 조금이라도 칠하면 구멍으로 보고, 고리 안쪽은 메운다.
     - 거리 변환으로 넓히고, 덩어리를 나눠 묶는다(Kotlin).
  2. 창: 정사각형 S = max(512, 구멍 범위 + 2×128px, √(구멍 넓이/0.35)). 사진 끝에서는 안쪽으로 밀고, 길쭉하면 겹치는 창으로 나눈다(최대 8개).
  3. 창마다 `LevelView`로 읽어(GPU 2×2 평균 축소) 512²로 맞춘다. 알파를 풀고 입력을 만들어 `model.fill`을 부른다.
  4. 색·밝기: '원본 − 출력'을 둘레에서 재어 `HoleFill`로 구멍 안에 퍼뜨린다(복구 브러시의 막과 같다). 투명이 섞이면 알파도 둘레에서 잇는다.
  5. 얹기
     - 1024px 묶음마다 `ImageResizer.BICUBIC`으로 키운다.
     - 타일마다 '쓰기 몫 ∩ 선택 영역'만 소프트웨어로 바꿔 넣는다(`drawReplace` 방식).
     - 닿지 않은 타일은 그대로 함께 쓴다.
  6. 게인맵: 게인맵 크기에서 `HoleFill`로 채운다(`GainmapEdits.fill`).
  7. `raster.withTiles` → 새 `Document` → `history.commit`(한 단계).
- **규칙**
  - 보정 전 원래 픽셀을 읽는다(리터칭과 같다).
  - 빈 레이어는 그대로 둔다.
  - 칠해서 지우기는 선택 영역 안만 바꾼다. 'AI 채우기'는 선택 영역을 구멍으로 쓰고, 채운 뒤 선택은 남긴다.
- **메모리·오류**
  - `estimateNewBytes` = 타일 + 게인맵 타일 + 작업 64MiB + 아직 안 불렀으면 모델 몫(1단계 실측값).
  - `runEdit`가 `InpaintUnavailableException`을 잡아 알림("이 기기에서는 AI 제거를 쓸 수 없어요" + '스팟 복구' 버튼)을 띄운다.
- **상수**: `InpaintPlan` 한곳에 둔다(넓히기 r = max(3, ⌈4/f⌉) 모델 픽셀, 경계 부드럽게 2 모델 픽셀 등).

## 엔진 설계 ② AI 선택 (분할 모델)

- **모델(`SegmentModel`)**: `encode`(1024² 그림 → 특징 묶음, 사진마다 한 번)와 `decode`(특징 + 탭 좌표 → 256² 결과, 탭마다)로 나뉜다. 6단계에서 사용자가 고른 하나만 남긴다.
  - SAM 2.1 Tiny: 변환본(인코더 80MB + 디코더 17MB)을 쓴다.
  - MobileSAM 계열: 공식 가중치를 litert-torch로 직접 변환한다(안 되면 EfficientSAM-Ti·TinySAM).
- **`ObjectSegmenter`(오래 사는 서비스)**
  - 보이는 레이어를 합친 그림을 긴 변 1024px 이하로 그려(`PreviewRenderer`, GPU) 인코딩하고, 특징을 기억한다.
  - 기억의 열쇠는 보이는 레이어의 래스터 객체와 속성이다. 편집으로 그림이 바뀌면 다음 탭에서 다시 읽는다.
  - AI 선택이나 AI 제거를 고르면 미리 읽어 둔다. 처음 한 번은 몇백 ms~1초쯤 걸릴 것으로 보고, 처리 중 표시를 띄운다.
  - 같은 전용 스레드(`stratum-ai`)에서 돈다.
- **결과 → 마스크**
  - 256² 결과에서 세 후보 중 점수가 높은 것을 고른다.
  - 1024px 이하로 키워 0을 기준으로 안팎을 가르고, 작은 구멍·티끌을 지운다(Kotlin, 1024px 이하).
  - `MaskTiles.upscaledTiles`로 문서 크기 알파 타일을 만든다(자동 선택과 같다).
  - 경계를 사진에 맞추는 가이드 필터는 10단계(품질)에서 한다.
- **명령 `SelectObject(x, y, mode, segmenter)`**
  - 자동 선택(`SelectSimilarColor`)처럼 `combineSelection`으로 새로·더하기·빼기·교차를 합친다. 실행 취소 한 단계이고, 선택 영역은 지금처럼 프로젝트에 저장하지 않는다.
- **AI 제거의 탭**
  - ViewModel이 `segmenter.maskAt(x, y)`로 물체 마스크를 받아 반투명으로 보인다.
  - [지우기]를 누르면 `InpaintRegion(layer, InpaintArea.Masked(마스크), inpaintModel)`을 보낸다.

## 화면 설계

- **리터칭 'AI 제거'(넷째 하위 도구, `SubTool.RETOUCH_REMOVE`)**
  - 커버 옵션은 하위 도구 4칸 → 안내 → 크기 슬라이더 → [지우기] 버튼(물체를 골랐을 때만 켜짐)이다. 메인 띠는 크기 칸과 지우기 칸이다.
  - 끌어서 칠하기: 반투명 검정으로 보이고, 손을 떼면 지운다.
  - 톡 누르기: AI가 찾은 모양을 반투명과 윤곽선으로 보인다.
    - 다른 물체를 누르면 바꾸고, 칠하기를 시작하면 버린다.
    - 뒤로 가기는 먼저 고른 물체를 버리고, 그다음 도구를 닫는다.
  - 가져올 곳 표시는 없다. 세 손가락 좌우(경도)는 무시한다.
- **선택 도구 'AI 선택'(다섯째 하위 도구, `SubTool.SELECT_AI`)**
  - 물체를 톡 누르면 모양대로 골라진다. 지금의 새로·더하기·빼기·교차와 동작 버튼(모두·해제·반전·지우기·복사·잘라내기)을 그대로 쓴다.
  - 페더는 없다(AI 경계가 이미 부드럽다).
  - 커버 하위 도구 줄이 5칸이 되므로, 커버 폭에서 한 줄에 드는지 테스트로 확인한다.
- **선택 도구 'AI 채우기'**: 동작 줄에 버튼 하나를 둔다. 선택이 있을 때만 켜진다.
- **새 문구(`values/`·`values-ko/` 둘 다)**
  - `subtool_ai_remove`: "AI remove" / "AI 제거"
  - `retouch_hint_remove`: "지울 것을 덮어 칠하고 손을 떼면 AI가 둘레에 맞게 채워요. 물체를 톡 누르면 모양을 찾아 보여 줘요."
  - `retouch_remove_apply`: "지우기"
  - `edit_remove_unavailable`
  - `subtool_select_ai`: "AI select" / "AI 선택"
  - `select_hint_ai`: "물체를 톡 누르면 AI가 모양대로 골라요."
  - `selection_ai_fill`: "AI 채우기"
  - 처리 중 안내 문구
- **라이선스**: 설정 > 앱 정보에 LiteRT(Apache 2.0), MI-GAN(MIT 전문), 고른 분할 모델(Apache 2.0)을 적는다. '인터넷을 쓰지 않아요'는 그대로 둔다.

## 빌드

- `gradle/libs.versions.toml`, `engine/build.gradle.kts`: `implementation(libs.litert)`. 판은 1단계 시험으로 고른다. 딸린 Play·gms·guava·coroutines-guava는 빼 보고, 모델이 그대로 돌면 뺀 채로 둔다.
- `app/build.gradle.kts`: `androidResources { noCompress += "tflite" }`
- 모델 위치: `engine/src/main/assets/models/`에 MI-GAN, 고른 분할 모델, 각 라이선스 파일을 둔다. SHA-256은 문서에 남긴다.
- `AndroidManifest.xml`: 두 권한(그리고 부품을 남기면 그 서비스·receiver·활동)에 `tools:node="remove"`를 단다. OpenCL `uses-native-library`는 남긴다.
- `rules.keep`: LiteRT를 keep하고, 뺀 부품은 dontwarn으로 둔다.
- ABI는 지금처럼 4종을 담은 APK 하나로 둔다. `abiFilters`나 AAB는 출시 때 정한다.

## 단계 (단계마다 커밋 하나)

- **0. 기록 (승인 직후, 마침)**
  - `docs/progress.md`: '진행 중'(요청 원문 1·2, 의도 "말하지 않음"), 결정 기록 'AI 제거·AI 선택'(위 결정, '추천 수용' 표시, Claude 판단과 이유), 진행표 줄.
  - `docs/history.md` 맨 위에 항목을 하나 더한다.
  - 조사 보고서를 `docs/ai-remove-research.md`로 저장한다(다른 PC에서도 보이게, 링크는 저장소 상대 경로로).
  - CLAUDE.md 8장 문장을 고친다(아래 '문서').
  - 커밋: "AI 제거·AI 선택 조사·계획과 사용자 결정을 기록함"
- **1. LiteRT·MI-GAN 실측 (화면 변화 없음)**
  - 판을 고르는 시험 빌드를 하고, MI-GAN을 내려받는다(허락 뒤, SHA-256 대조).
  - 코드: `InpaintModel`, `LiteRtInpaintModel`, 빌드·매니페스트·keep, 라이선스 문구.
  - 테스트: `InpaintModelProbeTest`(GPU·CPU로 채우기), `PermissionGuardTest`(INTERNET·FOREGROUND_SERVICE가 있으면 실패).
  - 폴드8에서 잴 것: 불러오기 시간, 추론 가운데값, 메모리, CPU 대체, GPU 노드 수, 열.
  - 함께 확인할 것: 릴리스(R8) 빌드, 합친 매니페스트, APK 크기, 16KB 정렬.
  - 끝 기준은 폴드8 GPU에서 수십 ms 안팎이다. 못 미치면 멈추고 보고한다.
- **2. 순수 Kotlin 판단 로직(AI 제거)**
  - `InpaintArea`·`InpaintMask`·`InpaintPlan`·`InpaintTensor`와 `HoleFill.influence`(채널 수를 받는 판).
  - JVM 테스트: 고리 안 메우기, 창 밀기·나누기, NCHW 순서, 막이 밝기 차이를 줄이는지, 몫 밖은 원본 그대로인지.
- **3a. 리터칭 부품 꺼내기(동작 그대로)**
  - `RetouchStroke.kt`의 private `LevelView`·`Membrane`을 꺼낸다.
  - `ImageResizer.BICUBIC`과 `GainmapEdits.scaledSelection`을 internal로 바꾸고, `drawReplace`에 몫 그림을 받는 판을, `SelectionPreview.maskAt`을 더한다.
  - 기존 `RetouchStrokeTest`·`EffectStrokeTest`·`SelectionCommandsTest`·`HdrTest`가 그대로 통과해야 한다.
- **3b. 엔진 명령 `InpaintRegion`**
  - `Inpainter`, `GainmapEdits.fill`
  - `InpaintRegionTest`(가짜 모델): 마스크 밖 비트 그대로, 타일 함께 쓰기, 선택 영역 안만, 사진 끝, 빈 레이어, 투명, 밝기 맞추기, 창 둘, 메모리 부족, 쓸 수 없음.
  - `HdrTest`·`WideColorTest`에 AI 제거 항목을 더한다.
- **4. 리터칭 'AI 제거'(칠하기)**
  - `EditTools.kt`, `RetouchTool.kt`, `ToolStrips.kt`, `EditScreen.kt`, `EditorViewModel.kt`, `ui/StratumApp.kt`, 아이콘, 문구, 설정 라이선스.
  - 테스트: `RetouchToolTest`, `OptionStripTest`, 새 `EditorRemoveTest`, `ToolSelectionTest`.
  - 에뮬레이터 Pixel_Fold의 세 배치와 폴드8에서 확인한다. 릴리스 빌드에서도 지우기가 되는지 본다.
- **5. 선택 도구 'AI 채우기'**: `SelectionActions.aiFill`, 버튼·띠 칸, `EditorViewModel.fillSelection()`. 테스트는 `SelectionToolTest`, `OptionStripTest`, `EditorSelectionTest`.
- **6. AI 선택 모델 비교 실측 → 사용자 결정**
  - 준비(각각 허락을 받고 함)
    - SAM 2.1 Tiny 변환본을 내려받는다(huggingface.co/mlboydaisuke/SAM2-hiera-tiny-LiteRT, 약 97MB).
    - MobileSAM 가중치(38.8MB)를 받고, 맥에 변환 도구(파이썬 가상환경의 PyTorch·litert-torch)를 설치해 변환한다. 변환이 안 되면 EfficientSAM-Ti·TinySAM으로 해 본다.
  - 시험 테스트: `SegmentProbeTest`. 시험 사진 10장쯤(사람·차·간판·전선·동물)에 정해 둔 탭 자리로 돌린다.
    - 잴 것: 인코더·디코더 시간, 메모리, 파일 크기
    - 찾은 모양을 사진 위에 겹친 그림으로 저장해 보여 준다.
  - 결과 표와 그림을 보여 주고 사용자가 고른다. 큰 파일을 git에 그대로 넣을지 LFS를 쓸지도 함께 묻는다(GitHub는 50MB가 넘는 파일에 경고하고, 100MB가 넘으면 받지 않는다).
  - 고른 모델만 커밋한다.
- **7. AI 선택 엔진**
  - `SegmentModel` 구현, `ObjectSegmenter`(특징 기억·미리 읽기), 결과 → 마스크(Kotlin, 1024px 이하), `SelectObject` 명령.
  - JVM 테스트: 좌표 바꾸기, 키우기·문턱, 티끌 지우기.
  - 에뮬레이터 테스트(가짜 모델): 선택 한 단계, 모드 합치기, 그림이 바뀌면 다시 읽기.
- **8. 선택 도구 'AI 선택'**
  - 하위 도구, 옵션·띠·안내, 처리 중 표시.
  - 테스트: `SelectionToolTest`(5칸이 커버 폭에 드는지), `EditorSelectionTest`.
  - 폴드8: 사람·차를 톡 눌러 고르기, 더하기·빼기, 고른 뒤 '새 레이어로 복사'(추출).
- **9. AI 제거에서 탭해서 고르기**
  - 톡 → 반투명 표시 → [지우기], 칠하기·뒤로 가기와의 관계.
  - 테스트: `RetouchToolTest`, `EditorRemoveTest`(지우기는 `InpaintArea.Masked`, 뒤로 가기는 표시만 버림).
- **10. 품질(작은 커밋 여러 개)**
  - 잡음 맞추기(Immerkær로 σ를 재고 해시 잡음을 더함)
  - (선택) 결 옮기기
  - 50MP에서 창 상수 고치기
  - AI 선택 경계를 가이드 필터로 다듬기

## 문서

- **`docs/progress.md`**: 단계마다 '진행 중'·진행표·결정 기록을 갱신한다.
  - 설계 메모
    - Kotlin 반복은 1024px 이하에서만 한다.
    - AI 계산은 `stratum-ai` 스레드에서만 한다.
    - 의존성을 더할 때마다 합친 매니페스트와 `PermissionGuardTest`를 확인한다.
    - 픽셀을 다시 그리는 새 명령은 게인맵도 고친다.
  - 기술 주의점: ByteBuffer `create` 없음, 딸린 권한, Kotlin 메타데이터 판, XNNPACK fp16
- **`docs/history.md`**: 커밋마다 항목 하나. **`docs/emulator.md`**: 16KB 정렬 결과.
- **CLAUDE.md 8장** (이 계획 승인으로 반영)
  - 지금: "커버 화면 도구 탭: 1단계에서는 완성된 도구만 보여준다. **반창고(2단계)와 제거(3단계, AI) 탭은 숨긴다.**"
  - 바꿀 문장: "커버 화면 도구 탭: 완성된 도구만 보여준다. 반창고는 '리터칭' 도구로 넣었다. 개체 제거(3단계, AI)는 따로 '제거' 탭을 두지 않고 리터칭의 하위 도구 'AI 제거'와 선택 도구의 'AI 채우기'로, 피사체 선택(3단계, AI)은 선택 도구의 하위 도구 'AI 선택'(탭)으로 넣는다(2026-10-20 사용자 결정). 추출은 AI 선택 뒤 '새 레이어로 복사'로 한다. 아직 만들지 않은 도구는 숨긴다."
  - `EditTools.kt` KDoc도 같은 뜻으로 고친다.
- **`docs/plan.md`**: 사용자 문서라 고치지 않는다. 필요하면 따로 묻는다.

## 위험과 대응

| 위험 | 대응 |
|---|---|
| LiteRT 2.3.0이 Kotlin 2.2.10에서 빌드되지 않음 | 1단계 첫 시험으로 확정하고, 안 되면 2.2.0 |
| 권한이 몰래 붙음(확인됨) | `tools:node="remove"`, 부품 빼기, `PermissionGuardTest`, 합친 매니페스트 확인 |
| 50MP에서 큰 구멍이 흐리고 결이 다름 | 창 나누기, 색 맞추기, 10단계 잡음·결. '작은·중간 물체용'으로 안내 |
| 기기마다 속도 차이, CPU 대체 실패(픽셀 8a에서 XNNPACK이 fp16을 거부) | 1·6단계에서 잼. 안 되면 '쓸 수 없음' 알림과 스팟 복구 안내 |
| 분할 모델의 처음 읽기(몇백 ms~1초)와 큰 메모리(퀄컴 NPU에서 1.3~2.3GB 보고) | 도구를 고를 때 미리 읽기, 처리 중 표시. 1024px 그림으로 돌리고 다 쓰면 내려놓음. 6단계에서 잼 |
| MobileSAM 직접 변환 실패(GPU가 5차원 이상 텐서를 받지 않음 등) | EfficientSAM-Ti·TinySAM으로 해 보고, 안 되면 SAM 2.1 Tiny |
| 가는 물체(전선)는 분할 모델이 놓침 | 칠해서 지우기로 안내(같은 도구 안에서) |
| 큰 모델 파일과 저장소(GitHub 50MB 경고·100MB 한도) | 6단계에서 LFS를 쓸지 묻고, SHA-256을 문서에 남김 |
| 학습 데이터 약관의 회색 지대(Places365 '연구 목적', SA-1B '연구용') | 저작권자가 가중치를 MIT·Apache-2.0으로 공개. 고지를 넣고 결정 기록에 남김 |
| 에뮬레이터는 소프트웨어 GPU에 OpenCL이 없음 | 가짜 모델로 테스트, 진짜 모델 테스트는 Assume, 속도는 폴드8에서 |
| 처음 만드는 릴리스(R8) 빌드 | 1단계에서 빌드, 4단계에서 실제 동작 확인 |

## 확인 방법

- **단계마다**: `./gradlew assembleDebug`, `./gradlew test`
  - 엔진 명령·화면은 해당 에뮬레이터 테스트 클래스만 돌린다. 테스트 수를 결과 XML로 확인한다(`docs/emulator.md`).
  - 합친 매니페스트에 INTERNET·FOREGROUND_SERVICE가 없어야 한다.
- **폴드8 (사용자 확인)**
  1. 커버: 리터칭 메뉴 넷째에 'AI 제거'가 있나요? 사람·전선을 칠하고 손을 떼면 1초 안팎에 지워지나요? 실행 취소 한 번이면 돌아오나요?
  2. AI 제거에서 사람을 톡 누르면 모양이 반투명으로 보이고, [지우기]로 지워지나요? 다른 사람을 누르면 바뀌나요?
  3. 선택 도구 'AI 선택': 차를 톡 누르면 차 모양대로 골라지나요? 더하기로 옆 사람도 고를 수 있나요? '새 레이어로 복사'로 꺼내지나요(추출)? 'AI 채우기'로 지워지나요?
  4. 메인 세로·가로: 띠의 칸이 맞고 접히는 선 위에 버튼이 없나요? 확대해서 둘레와 자연스럽게 이어지나요? 지운 곳 밖은 그대로인가요?
  5. 지우는 도중 접거나 펴도 결과가 남고 멈추지 않나요?
  6. 50MP 사진에서 작은 것·사람·자동차를 지우는 시간과 자연스러움은 어떤가요? 열 번 연속 지워도 괜찮나요(발열)?
  7. 비행기 모드에서도 다 되나요? 숨긴 레이어 알림이 뜨나요? HDR 사진을 지운 뒤 내보내도 지운 자리만 밝기가 튀지 않나요?
  8. 설정 > 앱 정보에 라이선스가 보이고 '인터넷을 쓰지 않아요'는 그대로인가요? 앱 크기는 얼마나 늘었나요?
