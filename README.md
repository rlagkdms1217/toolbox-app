# 툴박스 앱 (아이폰·안드로이드)

웹 툴박스 화면을 그대로 앱에 넣고, AI(누끼·지우기·화질 개선)는 폰의 전용 엔진으로 돌리는 앱이에요.

| 부분 | 어디에 | 설명 |
|---|---|---|
| 화면 | `www/` | 웹(`web` 폴더)과 같은 파일. 웹을 고치면 `npm run sync-web` 으로 복사 |
| AI 엔진 | `plugins/tb-ai/` | ONNX Runtime 1.30. 안드로이드 `android/…/TbEngine.java`, 아이폰 `ios/Sources/TbAIPlugin/TbAIPlugin.swift` |
| 안드로이드 프로젝트 | `android/` | Capacitor 8 |
| 아이폰 프로젝트 | `ios/` | Capacitor 8 (Swift Package Manager) |
| 클라우드 빌드 | `.github/workflows/` | 안드로이드 시험판 + AI 엔진 자동 시험 / 아이폰 빌드 확인 |

## 앱에서만 되는 것
- **폰 AI 엔진**: 아이폰은 CoreML(뉴럴 엔진), 안드로이드는 XNNPACK·멀티코어. 메모리 6GB 이상 폰은 새 누끼 모델(BiRefNet)까지.
- **저장**: 안드로이드는 사진 → 갤러리 '툴박스' 앨범, 문서 → 다운로드/툴박스. 아이폰은 공유 창(이미지 저장 / 파일에 저장).
- **공유로 열기**(안드로이드): 갤러리·카톡에서 공유 → 툴박스 → 맞는 도구를 골라 줘요.
- **인터넷 없이**: 화면·기기 AI 도구는 인터넷 없이도 돼요 (AI 글쓰기·요약 등 서버 AI는 인터넷 필요).

## 안드로이드 시험판 받기
1. GitHub 저장소 → **Actions** 탭 → "안드로이드 시험판 만들기"
2. 맨 위 실행 기록(초록 체크) 클릭 → 맨 아래 **Artifacts** → `toolbox-android-apk` 받기 (zip 안에 `.apk`)
3. 폰으로 `.apk` 를 옮겨서 열기 → "출처를 알 수 없는 앱 설치 허용" → 설치
   - 시험판은 서명 키가 고정이라, 다음 시험판은 지우지 않고 덮어 설치하면 돼요.

## 아이폰
- Actions → "아이폰 빌드 확인" → **Run workflow** (맥 클라우드 무료 사용량이 적어서 버튼으로만)
- 실제 아이폰에 설치·TestFlight 는 **애플 개발자 등록(연 $99)** 후, 인증서를 GitHub Secrets 에 넣으면 이어서 설정해요.

## 알려진 할 일
- [ ] 구글 로그인: 앱 안 웹 화면에서는 구글이 막아요 → 앱용 로그인(네이티브)으로 바꾸기 (Google Cloud 에 안드로이드·iOS 클라이언트 ID 필요)
- [ ] Worker `ALLOWED_ORIGINS` 를 쓸 때는 앱 주소 `https://localhost`, `toolbox://localhost` 도 넣기
- [ ] 아이폰 '공유로 열기'(Share Extension)
- [ ] 스토어용 서명(안드로이드 업로드 키, 애플 인증서) — 키는 GitHub Secrets 에만
- [ ] 앱 ID `kr.toolboxapp.app` — 스토어에 처음 올리기 전까지만 바꿀 수 있어요

## 개발자용
```bash
npm ci
npm run sync-web          # ../webpack2/web (웹 배포본) → www
npx cap sync              # www + 플러그인 → android/ios
cd android && ./gradlew assembleDebug
```
