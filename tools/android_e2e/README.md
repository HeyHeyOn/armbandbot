# 완장봇 Android E2E 자동 검증

목표: APK 빌드 후 Android 에뮬레이터에 설치하고 최소 실행/로그 확인까지 자동화한다.

## 스크립트

```bash
bash tools/android_e2e/ensure_avd.sh
bash tools/android_e2e/install_smoke.sh app/build/outputs/apk/release/app-release.apk
```

## 현재 호스트 상태

- Android SDK: `$HOME/Android/Sdk`
- Linux KVM 하드웨어 가속 사용 가능
- 생성한 AVD: `armbandbot_api35` (`google_apis/x86_64`, API 35)
- 기본 실행 자원: CPU 2코어, 메모리 3072MB, SwiftShader GPU

가속 상태 확인:

```bash
"$ANDROID_SDK_ROOT/emulator/emulator" -accel-check
```

AVD 부팅:

```bash
"$ANDROID_SDK_ROOT/emulator/emulator" \
  -avd armbandbot_api35 \
  -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot \
  -cores 2 -memory 3072
```

부팅 후 계측 테스트:

```bash
./gradlew connectedDebugAndroidTest
```

## 계정 분리 계획

외부 동작 E2E는 아래 세 역할을 분리한다.

1. 유동/비로그인 작성자
2. 로그인 고닉/반고닉 작성자
3. 완장 계정: 완장봇 로그인/차단 수행/테스트 후 차단 해제

실제 글/댓글 작성, 삭제, 차단, 차단 해제는 실험실 갤러리(`laboratory1`)로 제한한다.
