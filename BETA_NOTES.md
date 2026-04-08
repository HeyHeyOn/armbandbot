# 완장봇 1.1.1 베타 기록

정식 푸시 전까지의 임시 패치노트입니다.
정식 릴리즈 시 이 문서를 바탕으로 패치노트/배포 문구를 정리합니다.

---

## 1.1.1 beta1

### 주요 변화
- 봇 설정 export/import 1차 기능 추가
- 현재 봇 설정을 JSON 파일로 내보내기 가능
- JSON 파일을 불러와 새 봇으로 가져오기 가능
- 민감정보/실행 상태 제외
  - 쿠키
  - 자동로그인 계정/비밀번호
  - 실행 상태
  - 복구 상태
- import된 봇은 항상 정지 상태로 생성되도록 처리

### 기술 포인트
- SharedPreferences 전체 복사 대신 화이트리스트 기반 export/import 적용
- 전용 transfer 로직 분리
- beta1 빌드 성공 및 APK 업로드 완료

---

## 1.1.1 beta2

### 주요 변화
- export/import 관련 UI/문자열 안정화
- JSON 저장 토스트 한글 깨짐 수정
- 스와이프 액션 버튼 한글 깨짐 수정
- 봇 목록 페이지에 설정 파일 불러오기 버튼 추가
- 불러오기 성공/실패 토스트 정상화

### 기술 포인트
- 문자열 깨짐 원인은 인코딩 문제보다 코드 내 깨진 하드코딩 문자열이었음
- beta2 빌드 성공 및 APK 업로드 완료

---

## 1.1.1 beta3

### 주요 변화
- export/import UI polish
- 내보내기/불러오기 아이콘 방향 교정
- 봇 목록의 불러오기 버튼을 우측 하단 + 버튼 바로 위 동일 크기 FAB로 이동
- 봇 상세 페이지의 내보내기 버튼을 아이콘-only에서 알약형 버튼 + `내보내기` 텍스트로 개선

### 기술 포인트
- 기능 자체보다 UX 직관성 개선 중심
- beta3 빌드 성공 및 APK 업로드 완료

---

## 1.1.1 beta4

### 주요 변화
- 봇 설정 export JSON에 `schemaVersion`과 `exportedByAppVersion` 메타 추가
- 구버전 설정 파일은 최대한 그대로 import 가능하도록 legacy fallback 유지
- 미래 schemaVersion 파일은 명확한 오류 메시지로 차단
- import 경로에 스키마 마이그레이션 엔트리포인트/placeholder 구조 분리

### 기술 포인트
- 기존 `appVersion` 필드는 호환성 유지를 위해 계속 함께 기록
- 현재는 schema v1 고정이며, 향후 버전 추가 시 migration 분기만 확장하면 됨
- beta4 빌드 및 APK 업로드는 이번 작업에서 검증/진행 예정

---

## 기록 규칙
- 이후부터는 베타 커밋이 생길 때마다 이 파일에 같은 형식으로 누적 기록
- 각 항목에는 최소한 아래를 남김
  - 버전
  - 주요 변화
  - 기술 포인트 또는 주의사항
  - 빌드/업로드 완료 여부


## 1.1.1 beta6

### ?? ??
- ?? ?? URL ??? page + search_pos ?? ??? ?? ???
- ?? ?? ????? search_pos ??? ????? ??
- ?? ?? ?????? ??? ?? ?? ???/?? ?? ??? ????? ??
- hidden input ?? search_pos ?? ?? ?? ?? ??

### ?? ???
- .bottom_paging_box href ???? ?? ?? ??? ??
- ?? ?? ????? page ??, ?? ??? ?? ?? ?? page=1 + ?? search_pos ?? ??
- beta6 debug ?? ?? ? APK ??? ?? ??

## 1.1.1 beta7

### 주요 변화
- 검색 모드/활동로그에서 깨져 보이던 페이지 디버그 로그 문자열 정상화
- 페이지 URL 진입, 매니저 권한 상태, 게시글 행 수, 댓글 수 변경 여부 로그를 읽기 쉬운 한글로 정리
- 검색 페이지 순회 실패 로그와 다음 검색 링크 판별 문자열 보정

### 기술 포인트
- 이번 깨짐은 런타임 인코딩 문제보다는 BotService.kt 내부 하드코딩 문자열 손상에 가까웠음
- 다음 검색 링크 탐색 문자열도 함께 복구
- beta7 release 빌드 성공

## 1.1.1 beta8

### 주요 변화
- 자동 복구를 3초짜리 1회성 예약에서 60초 watchdog 재예약 구조로 변경
- AutoRestartReceiver가 복구 대상 봇 유무를 직접 판단하고, 복구 필요 시 watchdog을 다시 걸도록 정리
- 부팅/앱 업데이트 직후에도 MainActivity 실행을 기다리지 않고 복구 대상 봇을 즉시 복원하도록 보강

### 기술 포인트
- BotService는 이제 복구 알람의 세부 구현을 직접 들고 있지 않고 watchdog 예약/취소만 위임
- should_restore_after_restart 플래그가 남아 있는 동안 watchdog이 유지되어 강제 종료 테스트 기준 복구 가능성을 높임
- beta8 debug/release 빌드 성공

## 1.1.1 beta9

### 주요 변화
- BootReceiver/AutoRestartReceiver에서 백그라운드 즉시 복구를 중단하고 pending/watchdog 유지 방식으로 보수화
- MainActivity가 포그라운드 진입 시에만 실제 복구를 시도하도록 변경
- 즉시 복구 실패 시 예외를 삼켜 앱 크래시 루프 대신 pending/watchdog를 유지하도록 안전장치 추가

### 기술 포인트
- beta8의 브로드캐스트 직접 startForegroundService 경로가 Android 버전에 따라 예외를 유발할 수 있어 우선 안정화 방향으로 차단
- 자동 복구는 더 보수적으로 동작하지만, 앱 '계속 중지됨' 현상 방지가 우선


## 1.1.1 beta10

### ?? ???
- ??? ??, ?? ???, ?? ??? ?? ??? ??
- ??? ??? ??? ? ???? ??? ??(`*_text`)? ?? ??
- ?? ??? `apply()` ?? `commit()`?? ?? ????? ??

### ?? ??
- `search_keywords`, `normal`, `bypass`? ?? `StringSet` ??? ??? ??? ??? ??
- ??? ? `*_text`? ??? ?? ????, ??? ?? `StringSet` ??? fallback
- release ?? ??

## 1.1.1 beta11

### �ֿ� ��ȭ
- �α��� ���� ���� �Ǵ� ������ ���� �� �α��� �ʿ� ���� �� ����� �ڵ� �α��� ������ ��� ��α��� �õ�
- �ڵ� �α��� ���� �� saved_cookie�� �����ϰ� ���� �۾� ������ �̾ �簳�ϵ��� ����
- �ڵ� �α��� ���� ���� 3ȸ �� 10�� ��ٿ��� �ɾ� ���� ��õ� ������ ����
- ���� ������ �α��� �ʿ並 �и��� �α׸� �����, ���� ������ ��α��� ��� ��� �ߴ��ϵ��� ����

### ��� ����Ʈ
- ���� ���ο��� msign �α��� �������� �ٽ� �޾� conKey/csrf ��� POST �α��� �� ���� ��ȿ�� �����
- ���� Ƚ���� ������ ���� �ð��� SharedPreferences�� ������ ��õ� ���� ����
- beta11 ����/���ε�� �̹� �۾����� ���� ����


## 1.1.1 beta12

### ?? ??
- ?? ???(`s_type`, `s_keyword`, `search_pos`)??? ??? ?? ??? ? ????? ?????.
- ?? ?? ???? ??? ??? ??/????? ?? ??? ???? ??? ??????.
- ?? ?????? ??? ??? ???? ?? ?? ???? ? ? ? ???? ??? ??? ??? ?????.

### ?? ??
- ?? ??? URL, ??? ?, ??? ??? ?? ??? ?? ??? ?????.
- ?? ?????? ?? ??? ????? `LOGIN_REQUIRED`? ???? ??? ?? ??????.

## 1.1.1 beta13

### 주요 변화
- 세션 복구를 서비스형 자동 로그인 1차 시도 + WebView 로그인 fallback 2차 진입 구조로 연결
- 자동 로그인 실패 시 서비스가 복구 사유와 fallback 필요 상태를 UI에 브로드캐스트하도록 정리
- 수동/WebView 로그인 성공 후 saved_cookie 저장, 세션 복구 플래그 해제, 봇 재시작 흐름을 공통 처리
- `LOGIN_REQUIRED`는 fallback 대상으로 넘기고 `NO_PERMISSION`은 기존처럼 중단하도록 구분 유지

### 기술 포인트
- `session_login_required`, `session_webview_fallback_pending`, `session_recovery_reason` 프리퍼런스 상태 추가
- BotDetailScreen이 복구 브로드캐스트의 extras를 받아 WebView fallback을 바로 열 수 있게 보강
- 무한 복구 루프를 줄이기 위해 fallback 대기 상태는 로그인 성공 시에만 해제되도록 정리

## 1.1.1 beta14

### 주요 변화
- 게시글 전용 2차 검사 기능인 `AI 필터`를 추가하고, 기존 rule-based 1차 필터 통과 후에만 동작하도록 연결
- 봇별 AI 설정 UI를 추가해 Endpoint, API Key, 모델, 사용자 프롬프트를 저장할 수 있게 구성
- AI 응답은 고정 JSON만 허용하고, 파싱 실패/모순/호출 실패 시 자동으로 `REVIEW` fallback 처리
- review 우선 모드를 기본값으로 적용해 AI의 `BLOCK` 응답도 우선 검토 대상으로 수렴되도록 보수적으로 처리

### 기술 포인트
- `AiFilter.kt` 별도 모듈을 추가해 API 호출, JSON 파싱, 판단 결과 정규화를 서비스 로직과 분리
- `BotService`에는 최소 범위로 설정 로드, 게시글 분석 후 2차 AI 평가, AI 차단/검토 로그 프레젠테이션만 추가
- 댓글 필터 흐름은 유지하고 AI 필터는 게시글에만 적용되도록 분리
- 봇 설정 export/import 키에 AI 필터 관련 프리퍼런스를 포함


## 1.1.1 beta15

### 주요 변화
- AI 필터에 `provider` 개념을 추가해 OpenAI-compatible과 Gemini direct를 같은 흐름에서 선택 가능하게 확장
- 설정 UI에서 AI 제공자 선택 드롭다운을 추가하고, Gemini direct 선택 시 기본 generateContent 경로를 사용할 수 있게 보강
- Gemini direct 요청 본문/인증/응답 파싱을 `AiFilter.kt`에 추가하고, 실패 시 기존처럼 REVIEW fallback을 유지
- 게시글 전용 AI 2차 검사, review-first, 기존 OpenAI-compatible 동작은 그대로 유지

### 기술 포인트
- `AiFilterConfig`에 provider enum을 추가하고 OpenAI-compatible/Gemini direct별 payload 및 응답 파싱 분기 처리
- `BotService`는 설정 로드와 provider 전달만 최소 수정해 기존 분석 흐름 침범을 줄임
- 봇 설정 export/import에도 `ai_filter_provider`를 포함해 복제 시 provider 설정이 함께 이동되도록 정리
- beta15 release 빌드 성공 및 APK 산출 완료


## 1.1.1 beta16

### ?? ??
- AI review-first ???? `REVIEW` ??? ?? ?? ??? ???, ?? ?? ??? ??/??? ??? ?? ??? ??? ??? ??
- AI ?? ?? fallback? ??? ??? ?? ?? ?? ?? ???? ??? review-first ??? ?? ??? ????
- ?? rule-based 1? ?? ? ?? ?? ??? ????, ??? ?? AI 2? ?? ??? ??? ??
- AI ?? ??? ??? ??? ??? ??? ?? ??? ??? ? ?? AI ??? ??

### ?? ???
- `BotService`? ??? ?? ??? `ALLOW / REVIEW_ONLY / BLOCK_EXECUTE`? ??? AI ??? ?? ??? ?? ???? ??
- review-only ???? block history? ?? ??? ??? `executeBlockRequest()`? ???? ??? ??
- `AiFilterClient`? provider/model/prompt/??? ?? ?? LRU ??? ??? Gemini direct / OpenAI-compatible ???? ???
- beta16 release ?? ?? ? APK ?? ??


## 1.1.1 beta17

### 주요 변화
- AI 필터 기능을 실사용 화면에서 숨겨, 현재 베타에서는 기존 rule-based 필터 중심으로만 운영되도록 정리
- AI 관련 설정 화면 진입 경로를 제거해 일반 사용자 기준으로 AI 필터가 보이지 않게 처리
- 설정 export/import에서도 AI 필터 관련 항목을 제외해 보류 상태 기능이 노출되지 않도록 정리

### 기술 포인트
- AI 필터 코드는 내부에 유지하고, UI/설정 노출만 감추는 방식으로 보류 상태 전환
- 추후 AI 필터 개발 재개 시 기능 삭제 없이 다시 노출 가능하도록 최소 침습 수정
- beta17 빌드/업로드 예정


## 1.1.1 beta18

### 주요 변화
- 자동로그인 흐름에 디시 모바일 로그인 선행 검증(`/login/access`) 단계를 추가
- 로그인 전 검증 성공 시 서버가 내려주는 `Block_key`를 반영한 뒤 실제 `/login` 제출을 수행하도록 수정
- 자동로그인 세션 확보 시 access/login 단계 쿠키를 함께 병합해 저장하도록 보강

### 기술 포인트
- 기존 구현은 `/login` POST만 수행해 실제 인증 완료 플로우를 충분히 재현하지 못하고 있었음
- 로그인 페이지 JS의 `loginRequest()` 흐름을 따라 `/login/access` → `Block_key` 갱신 → `/login` 순서로 맞춤
- beta18 빌드/업로드 예정


## 1.1.1 beta19

### 주요 변화
- 자동로그인/세션복구 경로에 단계별 진단 로그를 추가해 시작 직후 종료 원인을 더 잘 추적할 수 있게 보강
- `login/access` 사전 검증, 실제 로그인 제출, 세션 검증 결과를 각각 로그로 남기도록 정리
- 시작 직후 세션 복구 실패 시 즉시 내려가는 흐름을 조금 완화해 WebView 로그인 대기 전환 로그를 더 분명히 남기도록 조정

### 기술 포인트
- Galaxy S23에서 beta11 이후 시작 직후 종료처럼 보이는 현상을 추적하기 위한 진단 중심 베타
- 자동로그인 enabled/id/pw/current cookie 상태와 각 단계 성공/실패를 확인할 수 있게 로그 보강
- beta19 빌드/업로드 예정


## 1.1.1 beta20

### 주요 변화
- 서비스 시작 초반 보호 로직을 추가해 `startForeground()` 실패 시 앱이 무조건 뻗지 않도록 방어
- 서비스 시작 단계(`intent_received`, `job_creating`, `run_loop_entered`, `run_loop_crash`, `startForeground_failed`)를 기록해 다음 실행에서 직전 실패 지점을 추적할 수 있게 보강
- 이전 시작 실패 흔적이 있으면 다음 실행 시 활동 로그에 요약을 남기도록 정리

### 기술 포인트
- Galaxy S23에서 beta11 이후 시작 직후 앱 전체가 종료되는 현상을 잡기 위한 보호/진단 중심 베타
- 로그를 못 남기고 죽는 경우를 대비해 SharedPreferences에 마지막 시작 단계와 상세 오류를 저장
- beta20 빌드/업로드 예정


## 1.1.1 beta21

### 주요 변화
- watchdog exact alarm 예약을 예외 안전하게 감싸, Galaxy 기기에서 예약 실패 시 앱이 즉시 종료되지 않도록 보강
- exact alarm 예약 실패 시 watchdog 없이도 봇 루프가 계속 진행되도록 완화
- 시작 단계 추적에 `watchdog_schedule_failed` 마커를 추가해 다음 실행에서 예약 실패 여부를 확인할 수 있게 정리

### 기술 포인트
- Galaxy S23에서 시작 직후 앱 전체 종료가 `scheduleAutoRestart()` 주변일 가능성에 대응한 안전화 베타
- `AutoRestartReceiver.scheduleWatchdog()`가 성공/실패를 반환하도록 바꾸고, `BotService`는 실패를 로그로 남기고 계속 진행
- beta21 빌드/업로드 예정


## 1.1.1 beta22

### 주요 변화
- watchdog 예약 시 exact alarm 가능 여부를 확인하고, 불가한 기기에서는 `setAndAllowWhileIdle` fallback으로 자동 전환
- watchdog 예약 결과를 `exact`, `exact_legacy`, `inexact_fallback`, `failed`로 구분해 로그에 남기도록 보강
- exact alarm 실패/제한 시에도 봇 자체는 계속 동작하고, 복구 전략만 기기 상태에 맞춰 낮춰서 적용하도록 정리

### 기술 포인트
- Galaxy S23에서 exact alarm 예약이 앱 시작 안정성에 영향을 주는 점을 확인한 뒤, 기기별 권한/정책 상태에 맞춘 fallback 전략을 추가
- `AutoRestartReceiver.scheduleWatchdog()`가 결과 객체를 반환하고, `BotService`는 mode/detail 기반으로 사용자 로그를 남김
- beta22 빌드/업로드 예정


## 1.1.1 beta23

### 주요 변화
- 활동 로그를 문자열 기반 임시 분류에서 `카테고리 태그 기반 분류`로 개편해 오인 분류를 줄임
- 활동 로그 UI에 `전체 / 탐색 / 차단 / 디버그 / 세션·복구 / 오류` 필터를 추가
- 메모리/파일 로그 보관량을 늘려 디버그 모드에서도 로그가 너무 빨리 밀리지 않도록 완화
- 활동 로그를 텍스트 파일로 직접 저장하는 기능 추가

### 기술 포인트
- 로그 파일은 단순 문자열 누적 대신 JSON line 기반으로 저장해 이후 분류/확장에 유리하도록 정리
- 봇 이름에 특정 단어가 들어가도 문자열 포함 여부로 차단 로그로 오인되지 않도록 로그 생성 후 카테고리 판별 구조로 변경
- beta23 빌드/업로드 예정

## 1.1.1 beta24

### 주요 변화
- 활동 로그 필터 칩 영역을 가로 스크롤 가능하게 바꿔, 화면이 좁을 때도 뒤쪽 탭(오류 등)에 접근할 수 있도록 수정
- 활동 로그 필터 UI가 좁은 기기 화면에서 잘리지 않도록 개선

---

## 1.2.0 beta1

### 주요 변화
- AI 필터를 다시 사용자 설정 화면에 노출하고, 클라우드형 LLM 기반 설정(provider / endpoint / api key / model / prompt)을 복구
- AI 필터 입력 구조를 게시글 단건 중심에서 배치형 구조로 확장하고, 게시글/댓글 판정 결과를 분리해서 받을 수 있도록 재설계
- AI 검사 시 게시글 본문, 미디어, 댓글 전체를 함께 넘기는 방향으로 연결하고, 댓글만 차단 대상인 경우 댓글 차단 흐름으로 우선 연결
- 배치 기준값(최대 글 수 / 최대 대기 시간 / 최대 누적 용량) 설정을 추가하고, 여러 글 누적 큐 + 결과 캐시 구조를 1차 도입
- AI review / block 결과를 활동 로그와 후속 처리 흐름에 반영하고, 중복 큐 적재/중복 댓글 차단을 줄이기 위한 1차 안정화 적용

### 기술 포인트
- `AiFilter.kt`를 배치 요청/응답 구조로 확장하고 `AiBatchQueue.kt`를 추가해 beta1용 배치 큐 기반을 도입
- `BotService.kt`에서 게시글 상세 접근 시 수집한 원문/댓글 전체를 AI 후보로 적재하고, flush 시 배치 응답을 post/comment 단위로 캐시에 저장하도록 연결
- 현재 beta1은 배치 구조와 댓글 분리 집행 기반까지 들어간 상태이며, 이후 베타에서 운영 안정화와 UX를 더 다듬을 예정
- 1.2.0-beta1 debug 빌드 성공 및 APK 산출 완료

---

## 1.2.0 beta2

### 주요 변화
- `analyzePost()` 안에 남아 있던 게시글별 단건 AI 호출을 제거하고, AI 2차 판단이 배치 큐 경로에서만 처리되도록 정리
- AI 호출 실패/빈 결과/무의미한 결과가 캐시에 재사용되지 않도록 캐시 정책을 보수적으로 수정
- AI raw 응답 미리보기, 파싱 실패 사유, post/comment 매핑 실패 등 디버그 로그를 크게 보강
- 활동 로그 화면에 `디버그 로그+설정 저장` 기능을 추가해 디버그/오류/세션 로그와 현재 봇 설정값을 함께 저장 가능하게 개선

### 기술 포인트
- `AiFilter.kt`에 raw content 로그, HTTP 오류 로그, results/post_no/comment_id 파싱 실패 로그를 추가하고 실패/빈 결과 캐시를 금지
- `BotService.kt`에서 단건 AI fallback 경로를 제거해 배치 전용 구조로 더 명확하게 정리
- `BotDetailScreen.kt`에서 디버그 로그+설정 dump를 별도 txt로 저장하는 export 기능 추가
- 1.2.0-beta2 debug 빌드 성공 및 APK 업로드 예정

---

## 1.2.0 beta3

### 주요 변화
- 배치 AI 결과가 실제로 어떤 판단을 내렸는지 활동 로그에서 바로 확인할 수 있도록 게시글/댓글별 decision 로그를 추가
- AI 배치 요약 로그에 post/comment의 block/review 개수를 함께 표시하도록 보강
- 테스트 단계에서 실제 AI 차단 판정을 확인할 수 있도록 내부 `reviewMode` 강제 완화를 해제

### 기술 포인트
- `BotService.kt`에서 캐시에서 회수한 `AiFilterPostDecision`과 `AiFilterCommentDecision`을 사람이 읽을 수 있는 로그 형식으로 출력
- AI 배치 요약 로그를 `post(block=?, review=?) / comment(block=?, review=?)` 형태로 확장
- 배치 호출 시 `reviewMode = false`로 전환해 모델의 `BLOCK` 응답이 내부에서 자동 REVIEW로 낮아지지 않도록 조정
- 1.2.0-beta3 debug 빌드 성공 및 APK 업로드 예정

---

## 1.2.0 beta4

### 주요 변화
- Gemini direct 등 AI provider에서 429/5xx 계열 일시 오류가 날 때 즉시 포기하지 않고 자동 재시도하도록 개선
- AI 배치 호출 실패 시 해당 묶음을 버리지 않고 다시 큐에 넣어 다음 사이클에서 재시도하도록 보강
- 활동 로그에 일시적 서버 오류/재시도 예정/재큐 처리 여부가 드러나도록 운영형 오류 로그를 추가

### 기술 포인트
- `AiFilter.kt`의 HTTP 호출부에 429/500/502/503/504 재시도(backoff) 추가
- `BotService.kt`에서 배치 평가 실패 시 flush된 항목들을 다시 `AiBatchQueue`에 적재하도록 정리
- 일시적 서버 오류는 사용자 입장에서 원인 파악이 쉽도록 별도 안내 로그를 남기게 보강
- 1.2.0-beta4 debug 빌드 성공 및 APK 업로드 예정

---

## 1.2.0 beta5

### 주요 변화
- AI 필터가 실제로 어느 단계까지 진행됐는지 확인할 수 있도록 AI 경로 진입/큐 적재/flush 판정/호출 시작 로그를 추가
- 테스트 로그에서 AI 관련 정보가 보이지 않을 때, 실제로 배치 경로를 타지 못한 것인지 단순 결과 부재인지 더 명확히 구분 가능하게 개선

### 기술 포인트
- `BotService.kt`의 `processSinglePost()`에서 `[AI 배치] AI 필터 활성`, `[AI 배치] 후보 적재`, `[AI 배치] flush 판정`, `[AI 배치] 호출 시작` 로그를 추가
- 이후 로그만으로도 AI 실행 경로가 어디서 끊겼는지 빠르게 분석할 수 있도록 가시성 중심으로 보강
- 1.2.0-beta5 debug 빌드 성공 및 APK 업로드 예정

