# 차근차근

차를 안전하게 주차한 뒤 차량 기능을 짧게 연습하고, 앱이 읽은 차량 상태로 수행 여부를 확인하는 Android Automotive 프로토타입입니다. 설명서나 출고 안내를 대체하지 않습니다.

## 현재 앱

- **내 차 기능 익히기**: 시트 열선, 앞유리 서리 제거, 주행 보조 버튼을 상황별 안내로 연습합니다. 차량 신호 변화가 확인된 경우에만 차량에서 실습한 것으로 기록합니다.
- **다음 날 복습**: 다른 상황에서 같은 기능을 다시 시도합니다. 학습 효과는 실제 차량 사용자 테스트로 검증되지 않았습니다.
- **선택 콘텐츠 · 방탈출**: 1화 「지하 4층 정전」의 퍼즐 5개를 진행합니다.
- **안전 게이트**: P단, 속도 0, 네 문 닫힘, 신호 정상일 때만 시작합니다. 조건이 깨지면 멈추고 저장하며, 자동으로 재개하지 않습니다.

처음 실행할 때 음성+자막 또는 자막만, 보통 또는 큰 글자를 고릅니다. 홈의 「안내 방식」에서도 이 두 항목을 바꿀 수 있습니다. 자세히/짧게, 화면으로 대신하기, 조명 연출은 현재 설정 화면에서 선택할 수 없습니다.

## 개발 및 검증 환경

- Pleos Connect Emulator 2.1.0 (Android Automotive API 34, arm64), Virtual Console
- 에뮬레이터 화면 규격: 2560 × 1440 px
- Kotlin 2.0.0, Jetpack Compose, Gradle wrapper 8.10.2, AGP 8.8.2, JDK 17
- Pleos Vehicle SDK 2.3.2, TextToSpeech SDK 3.3.1
- 단위 테스트: 2026-11-15 기준 121개 통과. 같은 날 서명된 Release APK를 빌드하고 서명을 확인했으며, Pleos Connect Emulator 2.1.0에 설치해 첫 화면 실행을 확인했습니다. 전체 화면 흐름과 실차 동작은 확인되지 않았습니다.
- 실차 동작은 확인되지 않았습니다.
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

## 제출용 Release APK

제출용 APK는 서명된 `release` 빌드입니다. 빌드 출력은 `app/build/outputs/apk/release/app-release.apk`이며, 제출용 파일은 `apk/김택수_차근차근_release.apk`에 있습니다.

Release APK에는 Pleos TTS Client ID와 Client Secret을 넣지 않습니다. 자막은 계속 표시되며, 음성은 기기 TTS 지원 상태에 따라 달라질 수 있습니다. 공개 저장소에는 `local.properties`, `signing.properties`, `signing/`을 올리지 않습니다. `signing.properties`는 각 빌드 담당자가 관리하는 키스토어 경로·별칭·비밀번호 설정 파일입니다.

Release 빌드에는 개인 키스토어와 프로젝트 루트의 `signing.properties`가 필요합니다. 키스토어는 한 번 생성해 안전하게 보관하고, 같은 앱의 이후 APK에도 같은 키를 사용합니다.

```properties
storeFile=signing/chageun-release.jks
storePassword=<키스토어 비밀번호>
keyAlias=chageun-release
keyPassword=<키 비밀번호>
```

차량 신호를 사용하는 검증에는 Pleos Connect 에뮬레이터와 Virtual Console이 필요합니다. 에뮬레이터를 콜드 부팅한 뒤 `adb forward tcp:8088 tcp:8088`을 설정하고 `tools/vc_scenario.py`를 사용합니다.

```bash
python3 -I tools/vc_scenario.py park
python3 -I tools/vc_scenario.py defrost on
python3 -I tools/vc_scenario.py cruise ready
python3 -I tools/vc_scenario.py demo
```

## 음성 및 자격 증명

디버그 빌드에서 Pleos TTS 서버 음성을 연결하려면 Pleos 개발자 콘솔에서 발급한 값을 프로젝트 루트의 `local.properties`에 둡니다. 이 파일은 Git에서 제외되며, 해당 값은 디버그 빌드에만 전달됩니다. Release APK에서는 두 값이 비어 있고, 화면 자막은 계속 사용할 수 있습니다.

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
