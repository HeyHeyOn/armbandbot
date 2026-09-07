# 완장봇 프로젝트 STATUS

## 기본 정보
- 종류: Android 앱 (Kotlin + Jetpack Compose)
- GitHub: https://github.com/HeyHeyOn/armbandbot
- 로컬 경로: projects/armbandbot/

## 현재 상태
- 개발용 베타: 1.5.2-beta1 (versionCode 170)
- 봇별 독립 검사 상태·예약 작동 시간대·전역 관리 작업 중복 방지 및 백업/스냅샷 보호 구현.
- JVM 564개, API 24·35 계측 각각 19개, lint/release 및 실제 상세 화면 진입 검증 완료.
- APK·사용 안내문 Drive 업로드와 다운로드 해시 검증 완료.
- 배포 링크와 검증 근거: `releases/BETA_NOTES.md`, `releases/1.5.2-beta1-verification.json`.

## 다음 단계
- 베타 실사용 피드백 및 실제 DC 연동·장시간 운용 확인 후 정식 전환 검토.

## 주요 파일
- BotService.kt - 봇 핵심 로직
- MainActivity.kt - UI
- AppDatabase.kt - DB
- AutoRestartReceiver.kt - 자동 재시작
- BootReceiver.kt - 부팅 시 시작
