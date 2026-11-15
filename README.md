# 차근차근

차를 안전하게 주차한 뒤 차량 기능을 짧게 연습하고, 앱이 읽은 차량 상태로 수행 여부를 확인하는 Android Automotive 프로토타입입니다. 설명서나 출고 안내를 대체하지 않습니다.

## 현재 앱

- **내 차 기능 익히기**: 시트 열선, 앞유리 서리 제거, 주행 보조 버튼을 상황별 안내로 연습합니다. 차량 신호 변화가 확인된 경우에만 차량에서 실습한 것으로 기록합니다.
- **다음 날 복습**: 다른 상황에서 같은 기능을 다시 시도합니다. 학습 효과는 실제 차량 사용자 테스트로 검증되지 않았습니다.
- **선택 콘텐츠 · 방탈출**: 1화 「지하 4층 정전」의 퍼즐 5개를 진행합니다.
- **안전 게이트**: P단, 속도 0, 네 문 닫힘, 신호 정상일 때만 시작합니다. 조건이 깨지면 멈추고 저장하며, 자동으로 재개하지 않습니다.

처음 실행할 때 음성+자막 또는 자막만, 보통 또는 큰 글자를 고릅니다. 홈의 「안내 방식」에서도 이 두 항목을 바꿀 수 있습니다. 자세히/짧게와 조명 연출을 고르는 설정은 없습니다. 화면으로 대신하기는 지원하는 퍼즐에서 선택할 수 있습니다.

## 개발 및 검증 환경

- Pleos Connect Emulator 2.1.0 (Android Automotive API 34, arm64), Virtual Console
- 에뮬레이터 화면 규격: 2560 × 1440 px
- Kotlin 2.0.0, Jetpack Compose, Gradle wrapper 8.10.2, AGP 8.8.2, JDK 17
- Pleos Vehicle SDK 2.3.2, TextToSpeech SDK 3.3.1
- 단위 테스트: 2026-11-15 기준 121개 통과. 같은 날 서명된 Release APK를 빌드하고 서명을 확인했습니다. Pleos Connect Emulator 2.1.0에서 첫 설정·홈, 열선 연습·다음 날 복습, 1화의 안내판·금고·핸들·공조·불빛·완료 흐름, 속도 신호에 따른 멈춤과 수동 재개를 확인했습니다. 실차 동작은 확인하지 않았습니다.
- 공개 저장소: https://github.com/KOTEAKS/chageun
- 제출용 서명 APK: `apk/김택수_차근차근_release.apk` (저장소 내 경로)

## 빌드 및 테스트

JDK 17과 Android SDK가 설치된 환경에서 프로젝트 루트(`chageun/`)에서 실행합니다.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 에뮬레이터에 설치하려면 다음을 실행합니다.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.letsgo.chageun/.MainActivity
```

첫 실행 시 Android Automotive가 표시하는 차량 속도 권한을 허용해야 합니다. 권한 대화상자에는 「위치 정보」로 표시될 수 있지만, 앱은 주행 속도 신호를 읽으며 사용자 위치를 수집하거나 저장하지 않습니다. 시연은 안전하게 주차하고 P단·속도 0·네 문 닫힘 상태에서 진행하세요.

## 제출용 Release APK

제출용 APK는 서명된 `release` 빌드입니다. 빌드 출력은 `app/build/outputs/apk/release/app-release.apk`이며, 제출용 파일은 `apk/김택수_차근차근_release.apk`에 있습니다.

제출용 Release APK는 Client ID와 Client Secret 없이 실행할 수 있습니다. 안내 자막은 항상 표시됩니다. TTS SDK는 HYBRID 모드이며, Pleos 안내에 따르면 네트워크 연결이 끊기면 기기 음성 엔진으로 전환합니다. 음성은 서버 연결과 기기 TTS 지원 상태에 따라 달라집니다. 공개 저장소에는 `local.properties`, `signing.properties`, `signing/`을 올리지 않습니다. `signing.properties`는 각 빌드 담당자가 관리하는 키스토어 경로·별칭·비밀번호 설정 파일입니다.

Release 빌드에는 개인 키스토어와 프로젝트 루트의 `signing.properties`가 필요합니다. 키스토어는 한 번 생성해 안전하게 보관하고, 같은 앱의 이후 APK에도 같은 키를 사용합니다.

```properties
storeFile=signing/chageun-release.jks
storePassword=<키스토어 비밀번호>
keyAlias=chageun-release
keyPassword=<키 비밀번호>
```

차량 신호를 사용하는 시연에는 Pleos Connect 에뮬레이터와 Virtual Console이 필요합니다. 에뮬레이터를 콜드 부팅한 뒤 포트를 연결하고 로컬 Virtual Console 페이지를 브라우저에서 열어 세션을 활성화합니다. 페이지를 열지 않으면 에뮬레이터와 브리지가 연결되지 않을 수 있습니다.

```bash
adb forward tcp:8088 tcp:8088
open -a "Google Chrome" http://127.0.0.1:8088/
curl http://127.0.0.1:8088/vehicle/driving-mode
python3 -I tools/vc_scenario.py park
python3 -I tools/vc_scenario.py defrost on
python3 -I tools/vc_scenario.py cruise ready
python3 -I tools/vc_scenario.py demo
```

상태 응답에서 `emulator_active`와 `bridge_ready`가 모두 `true`인지 확인한 뒤 시연합니다. `demo`는 명령마다 Enter를 눌러 진행하는 대화형 시나리오입니다. 앱이 시작되지 않으면 에뮬레이터 홈에서 차근차근을 다시 여세요.

다음 날 복습 배너는 Debug 빌드에서만 날짜를 바꿔 확인할 수 있습니다. 먼저 학습 기능의 상황 퍼즐을 완료한 뒤 앱을 다시 실행합니다.

```bash
adb shell am start -n com.letsgo.chageun/.MainActivity --ei dayOffset 1
```

홈의 복습 배너를 눌러 다른 상황의 복습을 진행합니다. `dayOffset`은 시연용이며 Release APK에서는 사용할 수 없습니다.

## 음성 및 자격 증명

Pleos TTS 서버 음성으로 개발·시험하려면 [Pleos Playground 개발자 안내](https://document.pleos.ai/docs/pleos-only/create-my-project)에 따라 계정을 만들고 `Workspace > My Project`에서 프로젝트를 생성합니다. 프로젝트 정보의 `SDK > Client ID`와 `Client Secret`을 로컬 `local.properties`에만 기록합니다. 이것은 심사용 APK 실행에 필요한 값이 아닙니다. `Project ID (Key)`는 Client ID·Secret과 별도 값입니다. 문서에서 TextToSpeech SDK가 `준비 중`으로 표시되면 사용 신청이 열리지 않을 수 있으니 프로젝트 화면에서 상태를 확인하세요. 발급한 비밀값은 소스·이슈·커밋·APK에 넣지 마세요.

```properties
pleos.clientId=...
pleos.clientSecret=...
```

자격 증명을 소스, 이슈, 커밋 또는 APK에 올리지 마세요. signing key와 `signing.properties`도 공개 저장소에 올리지 않습니다.

## 코드 구성

```text
app/src/main/java/com/letsgo/chageun/
  core/     정차 게이트, 퍼즐·에피소드 진행, 학습 기록과 복구 규칙
  vehicle/  Pleos Vehicle SDK와 AAOS Car API 연결
  audio/    TTS와 오디오 포커스
  ui/       Jetpack Compose 화면
  data/     기기 내 설정과 진행 기록
```
