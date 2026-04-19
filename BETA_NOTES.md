
## 1.3.0 beta1 (최신 업로드본)

### 주요 변화
- 1.3.0 작업 시작
- 차단/삭제 집행 공통 정책 구조(`ModerationActionConfig`) 추가
- `BLOCK` / `DELETE_ONLY` 모드 분기 추가
- 기본 차단 설정에 `차단 없이 삭제만 실행` 토글 추가
- 댓글 delete-only 경로 확정 및 동작 확인 (ajax/mini_manager_board_ajax/delete_comment)
- 게시글 delete-only 경로 확정 및 동작 확인 (ajax/mini_manager_board_ajax/delete_list, nos[]=targetNo)
- `ModerationActionOverride` / `loadModerationActionOverride` / `resolveModerationActionConfig` 공통 override 구조 추가
- 금지어 필터 개별 차단 설정 UI 추가 (금지어 필터 화면 하단)
  - 개별 차단 설정 사용 토글
  - 차단 시간 (기본 설정과 동일 옵션)
  - 차단 시 글/댓글 함께 삭제
  - 차단 없이 삭제만 실행
  - 사유 텍스트 (개별)
- 금지어 개별 차단 설정 실제 집행 연결 수정
  - 문자열(`blockReasonPrefix`) 추측 대신 명시적인 `filterSource=KEYWORD` 기반으로 override 적용
  - 게시글/댓글 모두 금지어 감지 시 `keyword_*` 설정이 실제 차단/사유/삭제모드에 반영되도록 수정
- 기존 차단+삭제 경로 회귀 수정
  - `update_avoid_list` 요청의 `del_chk` 값을 `Y/N`이 아니라 서버가 허용하는 `1/0`으로 복구
  - delete-only 추가 이후 깨진 기본 차단/삭제 요청 호환성 복구
- beta APK Drive 업로드를 서비스 계정으로 자동화 (수동 OAuth 불필요)

### 기술 포인트
- 게시글 삭제: mini/board/delete 상세 삭제 경로는 서버가 막아서 포기하고 목록 체크 삭제 AJAX 경로로 전환
- 금지어 필터 집행부는 `keyword_use_custom_action_config=true` 시 keyword_* 저장 키 기준 override 적용
- 개별 차단 설정 disabled 상태에서는 기본 차단 설정이 그대로 적용됨
- Drive 업로드는 service-account.json 경로의 서비스 계정(drive-uploader@armbandbot.iam.gserviceaccount.com)으로 처리
