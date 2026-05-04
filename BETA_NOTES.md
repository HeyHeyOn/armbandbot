
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
- 처리 방식 드롭다운 추가로 UI 구조 재정리
  - `삭제` / `차단`을 먼저 선택하고, `차단` 선택 시에만 차단 기간/차단 시 삭제/차단 사유가 보이도록 변경
  - 차단 기간 드롭다운은 원래 형태(시간만 선택)로 복구
  - 기본 차단 설정과 금지어 개별 차단 설정 모두 동일 구조로 통일
- 유저 필터 / 닉네임 필터 개별 차단 설정 1차 적용
  - UI에 `개별 차단 설정 사용` + `처리 방식(삭제/차단)` + 차단 상세 설정 추가
  - 서비스 집행부에서 `USER` / `NICKNAME` filter source 기준 override 적용
  - 저장 키: `user_*`, `nickname_*`
- URL 필터 / 보이스 필터 개별 차단 설정 1차 적용
  - UI에 `개별 차단 설정 사용` + `처리 방식(삭제/차단)` + 차단 상세 설정 추가
  - 서비스 집행부에서 `URL` / `VOICE` filter source 기준 override 적용
  - 저장 키: `url_*`, `voice_*`
- 이미지 필터 / 스팸코드 필터 개별 차단 설정 1차 적용
  - UI에 `개별 차단 설정 사용` + `처리 방식(삭제/차단)` + 차단 상세 설정 추가
  - 서비스 집행부에서 `IMAGE` / `SPAM` filter source 기준 override 적용
  - 저장 키: `image_*`, `spam_*`
- AI 필터 개별 차단 설정 1차 적용
  - AI 설정 화면에 `개별 차단 설정 사용` + `처리 방식(삭제/차단)` + 차단 상세 설정 추가
  - 저장 키: `ai_use_custom_action_config`, `ai_delete_only_mode`, `ai_block_duration_hours`, `ai_delete_post_on_block`, `ai_block_reason_text`
  - AI `BLOCK` 결과 집행 지점에서만 `ai_*` override 적용 (review/fallback에는 미적용)
- beta APK Drive 업로드를 서비스 계정으로 자동화 (수동 OAuth 불필요)

### 기술 포인트
- 게시글 삭제: mini/board/delete 상세 삭제 경로는 서버가 막아서 포기하고 목록 체크 삭제 AJAX 경로로 전환
- 금지어 필터 집행부는 `keyword_use_custom_action_config=true` 시 keyword_* 저장 키 기준 override 적용
- 개별 차단 설정 disabled 상태에서는 기본 차단 설정이 그대로 적용됨
- Drive 업로드는 service-account.json 경로의 서비스 계정(drive-uploader@armbandbot.iam.gserviceaccount.com)으로 처리

- debug: ����� �α� �������Ⱑ ��ü �α׸� �����ϵ��� ����, AI ��ġ ���л��� ����Ȯ�� ��Ŀ �߰�

- debug: AI ��ġ ��� ���� ��Ŀ(MARKER_AI_*_V2) �߰� �� ���� ������ ���� �ٷ� ����

- fix: AI ��ġ �������(Ÿ ��/Ÿ ���) ��θ� �ӽ� ��Ȱ��ȭ�� ���� �� �߽� ���� �帧���� ���

- debug: AI ���� ���� �α� �߰� (provider/model/key fingerprint/urlHasKey/header mode)

- debug: AI �α� ���ڵ�/ǥ�� ���� ȸ�ǿ����� ���� ���� �α� ���ο� auth/url/key ������ ����

- debug: AI ���� logger ��� BotService sendLog�� ȣ�� ��Ÿ(provider/model/endpointHost/urlHasKey/key����) ���� ���

- debug: �� AI �α� �� ��� ���� 'ȣ�� ����'/'AI ��ġ ȣ�� ����' ���ο� provider/endpointHost/urlHasKey/keyLen ���� ���� ����

- debug: ������ ���ϼ� ������ build stamp b8103ef�� ���� AI �α� ������ ���� ����

- debug: �ѱ� ���ڵ� ���� ������ ���� ASCII build stamp(AISTAMP/AIFAILSTAMP)�� AI �α� �� �տ� ����

- beta2 ����: ���ϸ�/�������� 1.3.0-beta2�� �����Ͽ� �� APK ���� �� ���ε� ��� �и� ����

- AI ��ġ ������� ����: ���� �� �� ��ġ ����� ��/��� �����ȹ���� �ٽ� �����ϰ�, ������� ���� �α׸� �߰�

- AI ��ġ ��ó�� ����: ��ġ���� �߰ߵ� �ٸ� ��/��� ���� ��ȹ�� �� ���� pending ��ȹ���� �����ϰ�, �ش� �� ��湮 �� �Һ� �α׿� �Բ� ���� ����ǵ��� ����

- AI ��ġ ������� ���� 2��: ���� immediate post/comment ��ó�� ������ ���� ai_override ��å �ؼ��� ������ ä �����ϰ�, ���� �� pending fallback ����

- 1.3.0 ����ȭ: AI ��û �غ�/���� �̸�����/���� ��Ÿ �� �ű� AI ���� ���� �α׸� ����� ��忡���� ����ϵ��� ����

- ���� �غ�: 1.3.0 / 1.3.1 ���� ���� ���� �߰� �� ������/Ȩ ȭ�� ǥ�⸦ 1.3.0���� ����

- ���� �� ����: AI ������ ��� ī��Ʈ �α� ���ŷ� ����� ���� �α׸� �߰� ����

- ���� ������: ���庿_v1.3.0.apk ���ε� �� Google Docs ��ġ��Ʈ �ǿ� 1.3.0 �׸� �ݿ�

- ���� ����: �Ŵ��� �ǿ� ���� ����/���ͺ� ���� ����/AI ���� � ���� �ݿ�, ��ġ��Ʈ ���� �������� ����1�������� �Ϲ� �ؽ�Ʈ ��Ÿ�Ϸ� ����

- ���� ����: ���� ���� ������ ó�� ����� ���� �帧(ó�� ��� �� ������ ���� �Ⱓ/���� �� ���� ���� ����)���� ���ۼ�

- ���� ������ ����: �Ŵ��� ���� ���� �帧�� AI ���� ���� ���� ��踦 �߰� ����

- ���� ���� ����: AI ���� ���� ����/� ���� AI ���� ���η� ���ġ�ϰ� ����/���� �ܶ� ��Ÿ���� �Ŵ��� ��Ģ�� �°� ����

- ���� ����: �Ŵ��� ������ 1.3.0���� �����ϰ� 4�� ���� ������ ����/�������� �ܶ� ��Ÿ���� �Ŵ��� ��Ģ�� �°� ����

- 1.3.1 �غ�: beta1 ���� ���� ��ȯ �� 1.3.1 �۾� ��ȹ ���� �߰�

- 1.3.1 �غ�: ��� �켱������ �ܰ躰 ���� ������ docs/roadmap_1_3_1.md �ε�� ������ ����

- 1.3.1-beta1: �����/PC ��� URL�� ����ۡ����Ӹ� ������ ���� stable list URL�� �����ϰ�, �˻��� ��忡���� ����� ���Ǹ� �����ϵ��� ����

- 1.3.1-beta1: �˻��� ��� �α��� ������� ���� ������ stable list URL�� �����ϵ��� ����

- 1.3.1 �غ�: ���� ���� 1�� ��å�� ���� �߽� �������� Ȯ���ϰ� �ε�� �� ���� �޸� ���� �߰�

- 1.3.1-beta1: ���� ���� 1���� �������� ��Ÿ�� ���� ����� BotService�� �߰�

- 1.3.1-beta1: ����/���� BLOCK_EXECUTE �߻� �� �ֱ� �̺�Ʈ�� �����ϰ� �Ӱ�ġ �ʰ� �� ���� ���� ���¸� �ڵ� Ȱ��ȭ�ϵ��� ��� �߰�

- 1.3.1-beta1: ���� ���� ���� Ȱ��ȭ �� �ű� ����/���� ���� ���� �������� �ٷ� ó���ϴ� ��� �߰�

- 1.3.1-beta1: ���� ���� ������ ���� BLOCK_EXECUTE ��ݿ��� �� ����/���� �� ���� ���� �������� ��ȯ

- 1.3.1-beta1: BotDetailScreen�� ���� ���� ���� ���� �׸�� ���� ���� ȭ�� �߰�

- 1.3.1-beta1: ���� ������ �� ���� �ð��� �ƴ϶� �Խñ� �ۼ� �ð� ��� ���� �������� �ٲٰ� anchor ���� �۸� �����ϵ��� ����

- 1.3.1-beta1: ���� ���� ���� ȭ�� ������ �ۼ� �ð� ��� ���� ��� �𵨿� �°� ����

- 1.3.1-beta1: ���� ������ ������ ���� ���� ��� ���� ���� �ߵ��ϵ��� ��ġ��, ������ ���� ���� �۵� ���� ��� �����ϵ��� ����

- 1.3.1-beta1: ���� ���� ���� ���� �� ��ȣ ������ ��� �����ϴ� ��ó���� ���� ���� ���� �α� �߰�

- 1.3.1-beta1: ���� ���� ����� ����, ���� ������ ���� ������ ���� ���� ���ذ����� ���� ����, ���� ���� ����â�� ���� ���� �ȳ� ���� �߰�

- 1.3.1-beta1: ���� ���� ���� ���տ� ���� �ݺ� �����Ǵ� ���� ����, ���� ���� ������ ���� ���� ������ ���� ��� ��� ���η� �̵�

- 1.3.1-beta1: 게시글/댓글 검사에서 꺼진 필터는 아예 실행하지 않도록 정리해 유저/닉네임/유동/깡계/금지어/URL/스팸코드/이미지/보이스의 불필요한 검사와 디버그 로그를 줄임

- 1.3.1-beta2: 꺼진 필터 검사 생략 수정본을 별도 베타2 파일명으로 재빌드하고 업로드해, 기존 beta1과 구분해서 재설치 검증할 수 있도록 정리

## 1.3.3 release

### 주요 변화
- 도배 방지 기능 추가: 최근 글의 작성 시각 간격을 기준으로 도배 상황을 감지하고, 감지에 사용된 샘플 글부터 이후 대상 글까지 삭제하도록 정리
- 일반/개념글/말머리/검색 감시 경로 정리 및 추천 페이지/특정 말머리/검색 추천 페이지 감시 지원 강화
- 검색 모드와 일반 모드의 페이지 순회 로직을 분리 유지하면서 URL 안정화 처리 개선
- 꺼진 필터는 실제 검사에서 제외하도록 최적화해 불필요한 검사와 디버그 로그 감소
- 디버그 성능 로그 추가: 페이지 fetch, 상세 fetch, 댓글 fetch, 스냅샷 저장, 게시글 전체 처리 시간 확인 가능
- 디버그 로그 검토 결과 beta2에서 켜진 필터만 실제 검사되는 동작 확인
- 2026-04-22 핫픽스 1차: mgallery(`gallType=M`) 게시글 삭제 요청이 일반 gallery 경로로 나가던 문제를 수정해, 게시글 삭제-only와 도배 방지 샘플/신규 글 삭제가 모두 `mini_manager_board_ajax/delete_list` 경로를 사용하도록 보정
- 2026-04-22 핫픽스 2차: 게시글 삭제 요청의 `ci_t`를 cookie의 `ci_c`가 아니라 상세 페이지에서 읽은 실제 `ci_t(tokenToUse)`로 전송하도록 수정
- 2026-04-22 핫픽스 3차: 실제 브라우저 cURL 기준으로 마이너(`M`)는 `minor_manager_board_ajax/delete_list`, 미니(`MI`)는 `mini_manager_board_ajax/delete_list`를 사용하고, 게시글 삭제 referer를 목록 URL로 맞춤
- 2026-04-22 핫픽스 4차: 도배 감지 샘플을 최신 글 전체가 아니라 유동/깡계 후보만 추린 뒤 그중 최신 N개로 계산하도록 수정
- 2026-04-23 핫픽스 5차: 도배 방지 깡계 후보 판정은 UID가 있는 작성자에만 적용되도록 정리해, 유동 글이 깡계로 섞여 보이거나 오판정되는 문제를 줄임
- 도배 방지 삭제 진단용 최소 디버그 로그 추가: 디버그 모드에서 `gallType`, `gallId`, `글번호`, 실제 `deleteUrl` 확인 가능

### 릴리즈 산출물
- versionCode = 50
- versionName = 1.3.3
- APK 파일명: 완장봇_v1.3.3.apk
- SHA256: 4688457FA01C2D9744BAAB8C3A8964EC62314C9912598577A431C254FD9BDD9B
- Release Drive file ID: 1st-5cq6m0yf7ZCNgmmv2_2pukxjXJ6gH
- Google Docs `완장봇 매뉴얼 & 패치노트`에 1.3.3 항목 추가 반영

## 1.3.4-beta1 구현 완료

### 주요 변화
- 해외 IP 필터 추가
- DC에 표시되는 IP 앞 두 자리 기준으로 한국 할당 IPv4 대역이 아니면 해외 IP로 판단
- 해외 IP 게시글/댓글 차단 토글 추가
- 해외 IP 차단 로그와 사유 문구 추가
- KRNIC 기준 한국 IPv4 대역을 앱 내부 목록으로 반영하고 중복 없이 정리

### 빌드 정보
- versionCode = 51
- versionName = 1.3.4-beta1
- APK: 완장봇_v1.3.4-beta1.apk
- SHA256: 6F18599320592BDC88E1EFFE0B061CC780DD4186DE743EA844FF26C8DD9EDB40
- Drive file ID: 1pziDpqOOiFSUkrl_sTNGuEFAtDzaQMmn

## 1.3.4-beta2 구현 완료

### 주요 변화
- 해외 IP 필터의 한국 IP 기준 목록을 IP2Location LITE KR 데이터로 확장
- DC에 표시되는 앞 두 자리 IP 기준 한국 prefix가 2,130개에서 2,927개로 증가
- 해외 IP 필터에도 개별 차단 설정 추가
  - 차단 없이 삭제만 실행
  - 차단 기간
  - 차단 시 글/댓글 함께 삭제
  - 차단 사유 개별 설정
- 해외 IP 게시글/댓글 차단 모두 개별 설정을 따르도록 연결

### 빌드 정보
- versionCode = 52
- versionName = 1.3.4-beta2
- APK: 완장봇_v1.3.4-beta2.apk
- SHA256: 91439E6264152DB92AA17ADFC27F3C62585174055880B6E01B333415349CA329
- Drive file ID: 13uxZtcROtND6immCGvSekKHvcTo5reT4

## 1.3.4-beta3 구현 완료

### 주요 변화
- 갤러리 설정 자동 갱신 기능 추가
- 봇 사이클 시작 시 마지막 갱신 성공 시각과 현재 시각을 비교해, 설정한 주기가 지났을 때만 갱신 요청 실행
- 실패 시 성공 시각은 갱신하지 않고 최소 5분 간격으로 재시도하도록 보호
- 마이너/미니 갤러리 모두 `update_ipblock` 저장 요청 지원
- VPN 제한, 전체 통신사 IP 제한, 특정 통신사 IP 대역 제한, 이미지/동영상 첨부 제한 갱신 설정 추가
- DC 드롭다운에 있는 허용 시간만 선택 가능하도록 고정 선택지 적용
- 여러 대상 URL이 있을 경우 갤러리별로 중복 없이 갱신 요청 수행

### 빌드 정보
- versionCode = 53
- versionName = 1.3.4-beta3
- APK: 완장봇_v1.3.4-beta3.apk
- SHA256: DD5B9606FD0CB229F951FF5994F66395A39A3E1A78F560D4B36912DB0A15B2A1
- Drive file ID: 1eRljHma1fHRYfXQm_ojDo5_FKSubtxuJ

## 1.3.4-beta4 구현 완료

### 주요 변화
- 봇 설정 메인 목록에 `갤러리 설정 자동 갱신` 진입 항목 추가
- 자동 갱신 상세 화면은 유지하고, 기본 탐색 설정 영역에서 바로 들어갈 수 있도록 연결

### 빌드 정보
- versionCode = 54
- versionName = 1.3.4-beta4
- APK: 완장봇_v1.3.4-beta4.apk
- SHA256: 5EBD32E8C4816BB0D7D948195BE8D5B01FD2644866201C1944D23AB6702A4942
- Drive file ID: 1BHUQF3EBTwsmrLJhDX4sgwmbJNEZQMuy

## 1.3.4-beta5 구현 완료

### 주요 변화
- 갤러리 설정 자동 갱신의 마지막 성공/시도 시각이 봇별 설정 저장소에 저장되는 구조 확인
- 봇을 새로 시작할 때 해당 봇의 마지막 갱신 성공 시각과 마지막 시도 시각을 초기화하도록 수정
- 긴급 설정 변경 후 봇 재시작 시 다음 사이클에서 즉시 갱신 요청을 보낼 수 있도록 개선

### 빌드 정보
- versionCode = 55
- versionName = 1.3.4-beta5
- APK: 완장봇_v1.3.4-beta5.apk
- SHA256: 3145A889461C69C14B5F0C7E6F044AF85E633FC507979F255F76316D27B903C7
- Drive file ID: 1AiMvDQmwpt5UVgHkfuIw3sJhj630g0e4

## 1.3.4-beta6 구현 완료

### 주요 변화
- 갤러리 설정 자동 갱신 상세 화면 UI 정리
- 마스터 토글이 꺼져 있으면 하위 설정을 흐리게 표시하고 조작되지 않도록 변경
- `IP 제한 갱신 payload` 문구를 `IP 제한 갱신`으로 변경
- VPN/전체 통신사 IP/특정 통신사 IP 대역 토글의 개별 설명을 제거하고, 상위 설명으로 통합
- 전체 상태 저장형 주의 문구를 마스터 토글 상세 설명으로 이동

### 빌드 정보
- versionCode = 56
- versionName = 1.3.4-beta6
- APK: 완장봇_v1.3.4-beta6.apk
- SHA256: C45B5B3009652540639717D152199433FB23DDC76CAAB48D0303A3CEC63EA965
- Drive file ID: 1In6DxtlxgCtenWEARBcJEEMVuirueY9d

## 1.3.4-beta7 구현 완료

### 주요 변화
- 갤러리 설정 자동 갱신 상세 화면 문구 정리
- 마스터 토글 설명을 짧고 명확하게 변경
- 갱신 주기 안내를 사이클 시작 기준 동작 설명으로 변경
- 이미지/동영상 첨부 제한 안내를 갱신 주기 기준 설명으로 변경

### 빌드 정보
- versionCode = 57
- versionName = 1.3.4-beta7
- APK: 완장봇_v1.3.4-beta7.apk
- SHA256: 03A60D55268487F7892747A71EF2AE89C875C8261844D66D989A5B427FEE04F0
- Drive file ID: 19G5QHUD1uKg8RFcT3j4Qh3bILfkwnJ0D

## 1.3.4-beta8 구현 완료

### 주요 변화
- 금지어 필터 적용 대상 제한 추가
- 금지어 필터 화면에 `유동에게만 적용`, `깡계에게만 적용` 토글 추가
- 두 토글이 모두 꺼져 있으면 기존처럼 모든 작성자에게 금지어 필터 적용
- 유동/깡계 토글 중 하나 이상 켜져 있으면 금지어가 감지된 경우에만 작성자 조건을 확인해 최종 차단 여부 결정
- 유동 판정은 기존 UID 없음 기준을 재사용하고, 깡계 판정은 기존 갤로그 글/댓글 수 기준과 캐시를 재사용

### 빌드 정보
- versionCode = 58
- versionName = 1.3.4-beta8
- APK: 완장봇_v1.3.4-beta8.apk
- SHA256: 7BAE1B021DC42C85EF0B8066D60372EC92F2D4F34963E7BF9F6060A13F8890C6
- Drive file ID: 1iyQafdAKchHAQ-UdLumA_lJgDA4ccxvs

## 1.3.4-beta9 구현 완료

### 주요 변화
- 깡계 판정 방식 선택 추가
  - 글+댓글 수
  - 글 수/댓글 수
  - 신규 고정닉 표시
- 글+댓글 수 방식에서는 글 수와 댓글 수의 합이 기준값 미만이면 깡계로 판단
- 글 수/댓글 수 방식은 기존처럼 둘 중 하나라도 기준 미만이면 깡계로 판단
- 신규 고정닉 표시 방식에서는 `newnik.gif`, `fix_newnik.gif`를 모두 감지해 디시 자체 신규 고정닉 표시 계정으로 판단
- 댓글은 댓글 API의 `gallog_icon` 필드에서 신규 고정닉 표시를 감지하도록 연결
- 신규 고정닉 표시 선택 시 설정 방법 안내 문구 추가

### 빌드 정보
- versionCode = 59
- versionName = 1.3.4-beta9
- APK: 완장봇_v1.3.4-beta9.apk
- SHA256: F4E6A82D66574DC872743080A466020C7980F44F87672D9D1E85E5DCE1CB139D
- Drive file ID: 1uRZ_uvzlOdafLCA2OSMc4k2g_Rzl8VWz

## 1.3.4-beta10 구현 완료

### 주요 변화
- 봇 상세 화면 진입 시 크래시 가능성 보완
- 새로 추가된 깡계 판정 방식/글댓합 기준 설정을 읽을 때 SharedPreferences 타입이 예상과 달라도 안전하게 기본값으로 처리하도록 수정
- 기존 설정 복사/백업/이전 베타 설치 상태에서 설정 타입이 꼬여도 상세 화면이 종료되지 않도록 보호

### 빌드 정보
- versionCode = 60
- versionName = 1.3.4-beta10
- APK: 완장봇_v1.3.4-beta10.apk
- SHA256: F77B9212FBEF0A7AA5DBB968BE5ABFFB825A0551552DA94E1A91DDD815A8D4EC
- Drive file ID: 1jq_pduzgVPrUf9bysb9swQt-Qho2U2wq

## 1.3.4-beta11 구현 완료

### 주요 변화
- beta9 이후 봇 상세 화면 진입 직후 종료되는 문제 대응 범위 확대
- 깡계 판정 방식 드롭다운 관련 상태/설정 읽기를 BotDetailScreen 최상단 초기화에서 제거
- 해당 상태는 실제 `깡계` 설정 화면을 열 때만 초기화되도록 변경하여, 봇 상세 기본 진입 경로와 신규 UI 상태를 분리
- beta10의 SharedPreferences 타입 안전 처리도 유지

### 빌드 정보
- versionCode = 61
- versionName = 1.3.4-beta11
- APK: 완장봇_v1.3.4-beta11.apk
- SHA256: E1CB2251175BB4D38C715814A22844960B1FC43CA35E616F4C5A7B0E984AA458
- Drive file ID: 1qDxCaEzdaYdt-PWJ1clD-F9tYwyD3cd5

## 1.3.4-beta12 구현 완료

### 주요 변화
- 도배 방지의 `깡계 대상` 판정을 깡계 필터 설정의 판정 방식과 통합
  - `글+댓글 수`, `글 수/댓글 수`, `신규 고정닉 표시` 모두 반영
  - 신규 고정닉 표시 방식에서는 게시글 작성자 영역의 `newnik.gif`, `fix_newnik.gif` 감지 결과 사용
- 금지어 필터의 `깡계에게만 적용` 설명에 현재 깡계 판정 기준 표시 추가
- 도배 방지의 `깡계 대상` 설명에 현재 깡계 판정 기준 표시 추가
- 봇 설정 내보내기/가져오기에 `kkang_detection_mode`, `kkang_total_min` 포함
- BotService 설정 로딩에서도 깡계 판정 방식/기준값을 타입 안전하게 읽도록 보강

### 빌드 정보
- versionCode = 62
- versionName = 1.3.4-beta12
- APK: 완장봇_v1.3.4-beta12.apk
- SHA256: A44759C10DEF7A1731513FF9CCB4B66D60102D8DDD99B573111DC1F1156BA81C
- Drive file ID: 1IfUUaer4QFbQFY1bhyi28rLwN4tFEEJ_

## 1.3.4-beta13 구현 완료

### 주요 변화
- 봇 목록 화면의 우측 하단 세로 플로팅 버튼을 하단 고정 가로 버튼 영역으로 변경
  - 순서: 봇 추가, 불러오기, 다크/라이트모드
  - 동일 크기 버튼, 중앙 정렬, 버튼 간격 적용
  - 봇 목록 스크롤 영역이 하단 버튼 영역과 겹치지 않도록 조정
- 활동 로그 화면의 우측 하단 세로 버튼을 로그 영역 아래 하단 고정 가로 버튼 영역으로 변경
  - 순서: 맨 위로, 맨 밑으로, 로그 저장, 디버그 로그 저장, 로그 삭제
  - 로그 내용과 버튼이 겹치지 않도록 분리
- 메인 화면 표시 버전을 1.3.4-beta13으로 갱신

### 빌드 정보
- versionCode = 63
- versionName = 1.3.4-beta13
- APK: 완장봇_v1.3.4-beta13.apk
- SHA256: 12A2E5564EB2F581342279B0A5220248EEE6B67B7D55CD60CD561769C79E7AC4
- Drive file ID: 1SWFKsv5js1_tZefzBME13ly3l5dtH2oj

## 1.3.4-beta14 구현 완료

### 주요 변화
- 봇 목록/활동 로그 액션 바 스타일 통일
- 두 화면 모두 하단 전체를 차지하는 얇은 액션 바 형태로 정리
- 액션 바 버튼별 배경/그림자 제거
- 아이콘 색상 통일
  - 라이트 모드: 짙은 남색
  - 다크 모드: 흰색
- 버튼 크기와 액션 바 두께를 줄여 더 가볍고 모던하게 조정
- 메인 화면 표시 버전을 1.3.4-beta14로 갱신

### 빌드 정보
- versionCode = 64
- versionName = 1.3.4-beta14
- APK: 완장봇_v1.3.4-beta14.apk
- SHA256: F5BF8E1FD3E5A3CAEBD4E2BD53A582897A0D8F416CCA412ECBB363CD695715DD
- Drive file ID: 1J5a9rglsDHrDEoJr1CHFn7_LzrudH4IK

## 1.3.4-beta15 구현 완료

### 주요 변화
- 봇 목록 하단 액션 바를 더 얇게 조정
  - 버튼 크기 축소
  - 상하 여백 축소
- 활동 로그 액션 바를 별도 블록처럼 보이지 않도록 조정
  - 둥근 모서리/그림자/상단 여백 제거
  - 하단에 붙은 전체 폭 액션 바 형태로 변경
  - 버튼 크기와 상하 여백 축소
- 활동 로그 `로그 저장` 아이콘을 저장 아이콘에서 내보내기 아이콘으로 변경
- 스냅샷 상세 페이지의 스냅샷 저장/추출 아이콘도 내보내기 아이콘으로 변경
- 메인 화면 표시 버전을 1.3.4-beta15로 갱신

### 빌드 정보
- versionCode = 65
- versionName = 1.3.4-beta15
- APK: 완장봇_v1.3.4-beta15.apk
- SHA256: 11DB269303485A8198316A0CFEC67FBE14A1AC7A79309833EEAB3B38CA5A4B60
- Drive file ID: 1_HSoQ-1ffywyEHQ0TDCqfpMOprzAc70-

## 1.3.4-beta16 구현 완료

### 주요 변화
- 활동 로그 액션 바가 화면 하단에 붙지 않고 떠 보이던 문제 수정
- 원인: 활동 로그/설정 탭 공용 콘텐츠 `Box`에 좌우 패딩이 적용되어 액션 바도 그 내부 폭과 위치에 갇혀 있었음
- 수정: 공용 콘텐츠 영역의 전역 좌우 패딩을 제거하고, 설정 탭/로그 필터/로그 박스에만 개별 패딩 적용
- 활동 로그 액션 바는 전체 폭을 사용하도록 분리되어 봇 목록 화면 액션 바처럼 하단에 붙도록 조정
- 메인 화면 표시 버전을 1.3.4-beta16으로 갱신

### 빌드 정보
- versionCode = 66
- versionName = 1.3.4-beta16
- APK: 완장봇_v1.3.4-beta16.apk
- SHA256: AEEE4D63F5E7B56E9918E8DA2D66A73E69938A43C7BF7AEB0C552A83DE27D0A6
- Drive file ID: 1rbskRFIzUa8ZlSPJrhNodqJ4X-OCsUCb

## 1.3.4-beta17 구현 완료

### 주요 변화
- 활동 로그 액션 바를 로그 콘텐츠 Column 내부에서 분리해, 로그 탭 전용 `Scaffold(bottomBar)`의 하단 바 영역으로 이동
- 이제 로그 필터/로그 박스와 액션 바가 같은 수준의 콘텐츠에 들어가지 않고, 액션 바가 별도 하단 영역으로 배치됨
- 봇 목록 화면처럼 액션 바가 화면 하단 전체 폭에 붙도록 구조 조정
- 기존 로그 저장/디버그 로그 저장/삭제/맨위/맨아래 동작 유지
- 메인 화면 표시 버전을 1.3.4-beta17로 갱신

### 빌드 정보
- versionCode = 67
- versionName = 1.3.4-beta17
- APK: 완장봇_v1.3.4-beta17.apk
- SHA256: 9EE334CCA1604D7A1B28241BC284E307D91CE76E0BB5CB76E7CBA4F4D4BA1748
- Drive file ID: 1tgPcZAEvdyuNuAfVgKFf6tmQXeqzdloM

## 1.3.4-beta19 구현 완료

### 주요 변화
- 봇 목록/활동 로그 하단 액션 바를 유튜브식 `아이콘 + 한글 라벨` 구조로 변경
- 봇 목록 라벨
  - 봇 추가
  - 불러오기
  - 다크 모드 / 라이트 모드
- 활동 로그 라벨
  - 맨 위로
  - 맨 아래로
  - 내보내기
  - 디버그
  - 로그 삭제
- 기존 아이콘 색상 정책 유지
  - 라이트 모드: 짙은 남색
  - 다크 모드: 흰색
- 메인 화면 표시 버전을 1.3.4-beta19로 갱신

### 빌드 정보
- versionCode = 69
- versionName = 1.3.4-beta19
- APK: 완장봇_v1.3.4-beta19.apk
- SHA256: 2A3206D436372CE21B343126C6C9A9DBC521A6EF7ADA9400480B1603758EA7F8
- Drive file ID: 1fpDkmY0upkTuuZ7Uu2sFg9gtt81SZLy3

## 1.3.4-beta20 구현 완료

### 주요 변화
- 하단 액션 바 아이콘 크기를 더 크게 조정
- 아이콘뿐 아니라 라벨까지 포함한 전체 영역이 버튼으로 동작하도록 변경
- 봇 목록 하단 액션 바에 DB 대시보드 이동 버튼 추가
  - 아이콘: 플로피디스크
  - 라벨: DB
- DB 대시보드 우측 상단에 DB 삭제 버튼 추가
  - 삭제 전 확인 팝업 표시
  - 공용 DB의 검사 기록/차단 기록 전체 초기화
- 메인 화면 표시 버전을 1.3.4-beta20으로 갱신

### 빌드 정보
- versionCode = 70
- versionName = 1.3.4-beta20
- APK: 완장봇_v1.3.4-beta20.apk
- SHA256: 02260B40F2F36D1BFFB7B86654ACDACA9D2D5ED29B06DF53A0E17AF687196129
- Drive file ID: 1yydxRrCNIm3OCd6-CnVzrT0hTktDHLMA

## 1.3.4-beta21 구현 완료

### 주요 변화
- 봇 목록의 DB 버튼 진입/복귀를 다른 상세 화면처럼 슬라이드+페이드 애니메이션으로 변경
- 봇 목록에서 연 DB 대시보드에서 시스템 뒤로가기 시 봇 목록으로 복귀하도록 수정
- 봇 목록 상단 안내 블록 제거, 목록 표시 공간 확대
- 완장봇 제목/버전 카드 우측에 도움말 버튼 추가
  - 기존 기본 안내 문구는 도움말 팝업으로 이동
- 활동 로그 필터칩 상단 공백 축소, 로그 영역을 위로 확장
- 봇 상세 시스템 관리 영역의 공용 DB 초기화 버튼 제거
- 개발자 설정의 DB 대시보드 버튼 제거
- DB 대시보드 상단 버튼 재배치
  - 정렬 버튼 오른쪽에 알약형 `초기화` 버튼 배치
  - X 없는 휴지통 아이콘 사용
- DB 초기화 확인 팝업에 현재 기록된 게시글 수 표시
- DB 전체 초기화 시 저장된 스냅샷 파일/스냅샷 캐시도 함께 삭제
- DB 대시보드 기록 행 스와이프 삭제 추가
  - 오른쪽 스와이프로 삭제 버튼 표시
  - 확인 팝업 후 해당 DB 정보와 스냅샷 삭제

### 빌드 정보
- versionCode = 71
- versionName = 1.3.4-beta21
- APK: 완장봇_v1.3.4-beta21.apk
- SHA256: AF46DF9AE8845AC3189F81D61A527C22D22CD1E29D24332A2EE41CB1501C7A54
- Drive file ID: 17vtC7HQRJ-sIa1mawAUxJy9FWoBvN4bk

## 1.3.4-beta22 구현 완료

### 주요 변화
- 봇 목록 하단 액션바의 버튼 아래 공백 최소화
- DB 대시보드 기록 스와이프 방향을 왼쪽으로 변경
- DB 대시보드 스와이프 삭제 버튼 크기/모양을 봇 목록 삭제 버튼과 통일
- DB 대시보드 정렬 버튼을 검색창 우측으로 이동하고 검색창 폭 조정
- DB 대시보드 검색에서 글 번호(postNum) 검색 지원
- 활동 로그 태그 버튼 상단 공백 추가 축소
- 활동 로그 줄 간격을 절반 수준으로 축소
- 메인 화면 표시 버전을 1.3.4-beta22로 갱신

### 빌드 정보
- versionCode = 72
- versionName = 1.3.4-beta22
- APK: 완장봇_v1.3.4-beta22.apk
- SHA256: 4B8E8CBA0303EAE8BBDE766F49DD726221CA0D6249384EC33417FFB2A8FA9710
- Drive file ID: 1wDisVKtFnttdcyr20fEm0jxzM-VErnVp

## 1.3.4-beta23 구현 완료

### 주요 변화
- 장시간 운용 중 런타임 자원 누적 방어 로직 추가
- 스냅샷 큐를 무제한에서 최대 80개 대기 제한으로 변경
- AI 배치 결과/대기 실행 계획/도배 이벤트 런타임 캐시 상한 정리 추가
- 봇 STOP/종료 시 해당 봇의 런타임 캐시와 헬스 상태 정리
- 사이클 시작/종료 시 런타임 캐시 정리 수행
- 활동 로그 메모리 상한을 5000개에서 3000개로 축소
- 1시간 주기 헬스 로그 추가
  - 메모리 사용량
  - 스레드 수
  - 활성 봇 수
  - 스냅샷 큐 대기 수
  - AI/도배/로그 메모리 상태
- 메모리 사용량이 높고 장시간 운용 중일 때 보수적 정리 요청 실행
- 헬스 로그를 별도 카테고리로 분류
- 활동 로그 필터에 `헬스` 탭 추가

### 빌드 정보
- versionCode = 73
- versionName = 1.3.4-beta23
- APK: 완장봇_v1.3.4-beta23.apk
- SHA256: D16FEF8E7A943073AEA7AD9EDE576D6FB85FE1B688EEF83A93D7DD0001C576ED
- Drive file ID: 1tmMnGcqAVwsaTuhCcCu4IafaBLjfad26

## 1.3.4-beta24 구현 완료

### 주요 변화
- DB 대시보드 개별 기록 삭제 범위 분리
- 공용 모니터링 기록 탭에서 일반 기록을 삭제해도 같은 글의 차단 상세 기록은 삭제하지 않도록 수정
- 차단 상세 기록 탭에서 차단 기록을 삭제해도 같은 글의 일반 기록은 삭제하지 않도록 유지
- 각 탭에서 삭제한 기록의 자체 스냅샷만 삭제
- 메인 화면 표시 버전을 1.3.4-beta24로 갱신

### 빌드 정보
- versionCode = 74
- versionName = 1.3.4-beta24
- APK: 완장봇_v1.3.4-beta24.apk
- SHA256: 0969F36E0A1A466CE15D51167BCF612CED9DE36A84E20B1B31B41EEE6447D385
- Drive file ID: 1Y61z4Q2zkjnzVyBAd9sDas1fP7zIxZaJ

## 1.3.4-beta25 구현 완료

### 주요 변화
- 일반 스냅샷과 차단 스냅샷의 DB 경로 분리 문제 수정
- 차단 발생 시 일반 DB 기록의 snapshotPath가 차단 스냅샷 경로로 덮이지 않도록 변경
- 일반 DB 기록 삭제 시 차단 스냅샷 HTML이 함께 삭제되던 문제 수정
- 일반 스냅샷은 최초/최신 구조 유지
  - 최초 저장 시 `_initial.html`만 저장
  - 이후 변경 저장 시 `_latest.html`을 새 내용으로 교체
  - 중간 일반 스냅샷 파일은 별도 누적하지 않음
- 스냅샷 뷰어가 `_initial.html`만 있는 첫 저장 상태와 `_latest.html`이 생긴 이후 상태를 모두 처리하도록 보강
- 메인 화면 표시 버전을 1.3.4-beta25로 갱신

### 빌드 정보
- versionCode = 75
- versionName = 1.3.4-beta25
- APK: 완장봇_v1.3.4-beta25.apk
- SHA256: 958B2ECF9A22C275221B45F8827782B17827DDFD57369D3F18F2924BD0C0D34A
- Drive file ID: 1BXIhzvz8ckKj973rSkRWP_11JOE4sTYD


## 1.3.4 official release (2026-04-26)

### Release
- VersionCode: 76
- VersionName: 1.3.4
- APK: 완장봇_v1.3.4.apk
- SHA256: BE87CEDF9FA05C3B23D4C35743FFE6901F12AC83FF340D40758F98FD10D4E9AF
- Drive file ID: 1xAzbaky0WkllgLBaXLqlk6_4UDc8Wl5J
- Drive link: https://drive.google.com/file/d/1xAzbaky0WkllgLBaXLqlk6_4UDc8Wl5J/view?usp=drivesdk

### 주요 반영
- 1.3.4-beta25 기반 정식 릴리즈 전환.
- 앱 표시 버전과 Gradle versionName을 1.3.4로 변경.
- Google Docs 매뉴얼/패치노트 탭을 1.3.4 기준으로 재작성.
- 매뉴얼은 신규 사용자도 흐름을 이해할 수 있도록 봇 목록, 활동 로그, 필터, AI, DB/스냅샷, 장시간 운용 섹션을 전체 맥락 기준으로 정리.
- 패치노트는 DB 대시보드, 스냅샷 분리, 깡계 판정 방식, 금지어 대상 제한, 갤러리 설정 자동 갱신, 헬스 로그/런타임 보호를 1.3.4 항목으로 반영.


## 1.3.5-beta1 (2026-04-26)

### 주요 변화
- 갤러리 설정 자동 갱신의 ci_t 토큰 확보 로직 보강.
- 기존에는 저장 쿠키의 ci_c 값만 사용했으나, 이제 갤러리 관리 페이지를 먼저 열어 input[name=ci_t] 값을 직접 추출합니다.
- 관리 페이지 토큰 추출에 실패할 때만 기존 ci_c 쿠키를 fallback으로 사용합니다.
- 토큰이 없을 때 로그를 ci_t 토큰 없음(관리 페이지/쿠키 모두 실패)로 구체화했습니다.
- 갱신 성공/실패 메시지에 토큰 출처(management_page 또는 cookie_ci_c)를 표시해 기기별 쿠키 차이 진단이 쉬워졌습니다.

### Release
- VersionCode: 77
- VersionName: 1.3.5-beta1
- APK: 완장봇_v1.3.5-beta1.apk
- SHA256: 6EA34B1C7EAB0FFF356F9D9CAC8EE528C443A09C88CB4BE43597F3B47CB27D9E
- Drive file ID: 164ryIvndVyI_dMIB8NsLvYGIyygj61nc
- Drive link: https://drive.google.com/file/d/164ryIvndVyI_dMIB8NsLvYGIyygj61nc/view?usp=drivesdk


## 1.3.5-beta2 (2026-04-26)

### 주요 변화
- 갤러리 설정 자동 갱신 토큰 확보 로직 추가 보강.
- 관리 페이지 GET 응답을 Connection.Response로 받아 HTTP status와 응답 쿠키를 확인합니다.
- 관리 페이지 응답 Set-Cookie의 ci_c를 확인하고, 있으면 POST 요청 Cookie에 병합한 뒤 ci_t로 사용합니다.
- 토큰 우선순위: 관리 페이지 input[name=ci_t] → 관리 페이지 응답 ci_c 쿠키 → 기존 저장 쿠키 ci_c.
- 실패 시 pageStatus, input 존재 여부, Set-Cookie ci_c 여부, 저장 쿠키 ci_c 여부를 로그에 포함해 원인 추적을 쉽게 했습니다.

### Release
- VersionCode: 78
- VersionName: 1.3.5-beta2
- APK: 완장봇_v1.3.5-beta2.apk
- SHA256: 80C4E8DA6D3C4853EF50AD7D8812BDA7A1C167F59C3A1569EE3129CE12A80233
- Drive file ID: 137p_aYqBjE32OCaTDatex7QFMA__dUba
- Drive link: https://drive.google.com/file/d/137p_aYqBjE32OCaTDatex7QFMA__dUba/view?usp=drivesdk


## 1.3.5 official release (2026-04-27)

### Release
- VersionCode: 79
- VersionName: 1.3.5
- APK: 완장봇_v1.3.5.apk
- SHA256: 50D4F2EF45748FC4457D7812BEFEAE109C3EE9EBB842EE2AC978634F634EB268
- Drive file ID: 1TAFZxJJ_uR61F1xf1GPYxgs-GqyIAmsL
- Drive link: https://drive.google.com/file/d/1TAFZxJJ_uR61F1xf1GPYxgs-GqyIAmsL/view?usp=drivesdk

### 주요 반영
- 1.3.5-beta2 검증 성공 후 정식 릴리즈 전환.
- 갤러리 설정 자동 갱신의 ci_t 확보 로직을 안정화했습니다.
- 관리 페이지 input ci_t, 관리 페이지 응답 ci_c, 기존 저장 쿠키 ci_c 순서로 토큰을 확보합니다.
- 응답 Set-Cookie의 ci_c가 새로 내려오면 POST 요청 Cookie에 병합해 갤러리 설정 저장 요청에 사용합니다.
- 일부 기기에서 오래된 로그인 쿠키에 ci_c가 없어 갤러리 설정 갱신이 실패하던 문제를 보완했습니다.

## 1.3.6-beta1 (2026-05-02)

### 주요 변경
- 차단 후속/기본 설정에 `차단 예외 글 설정` 추가.
- 줄바꿈으로 입력한 글 번호는 게시글과 해당 글의 댓글 전체를 차단 예외 처리.
- 예외 글 처리 시 기존 AI 대기/결과 계획도 정리하여 뒤늦은 AI 차단이 실행되지 않도록 보강.
- 설정 내보내기/가져오기에 차단 예외 글 번호 목록 포함.

### Release
- VersionCode: 80
- VersionName: 1.3.6-beta1
- APK: 완장봇_v1.3.6-beta1.apk
- SHA256: BB8811E7CA748BBB6D51CA31C13CE48F2911DF3A90556819312190A81CCC3B68
- Drive file ID: 12Hq9r12zGPcahKmgtL_jrpx--itdQ-bG
- Drive link: https://drive.google.com/file/d/12Hq9r12zGPcahKmgtL_jrpx--itdQ-bG/view?usp=drivesdk

## 1.3.6-beta2 (2026-05-02)

### 주요 변경
- `차단 예외 글 설정`을 차단 기본 설정 화면에서 분리.
- 차단 후속 동작 섹션에 별도 항목으로 배치해 필터별 개별 차단 설정과 무관하게 작동한다는 점을 명확히 표시.
- 예외 글 설정 상세 화면에 “게시글과 댓글 전체가 모든 차단 검사에서 제외됨” 안내 문구 추가.

### Release
- VersionCode: 81
- VersionName: 1.3.6-beta2
- APK: 완장봇_v1.3.6-beta2.apk
- SHA256: 8AAC9E9F012502C26D929FA5F00BD3B3E9B8B5A2547477686217B6CE61E07E40
- Drive file ID: 1ynOXC3jexBRz08KTFQf8_BJg2iy8PGDi
- Drive link: https://drive.google.com/file/d/1ynOXC3jexBRz08KTFQf8_BJg2iy8PGDi/view?usp=drivesdk

## 1.3.6-beta3 (2026-05-02)

### 주요 변경
- 차단 예외 글도 `모든 글 스냅샷`이 켜져 있으면 일반 글처럼 스냅샷을 저장하도록 수정.
- 예외 처리로 차단 검사는 건너뛰되, 전체 스냅샷 저장과 DB snapshotPath 기록은 유지.

### Release
- VersionCode: 82
- VersionName: 1.3.6-beta3
- APK: 완장봇_v1.3.6-beta3.apk
- SHA256: 1A309D73D12CBFC4A326A3799408C3C0E6B899E4D897AF7628459A1EC3ADE2C9
- Drive file ID: 19CEyvp7rHqClIjYcm3pN-CP3dB6Y2zkw
- Drive link: https://drive.google.com/file/d/19CEyvp7rHqClIjYcm3pN-CP3dB6Y2zkw/view?usp=drivesdk

## 1.3.6 official release (2026-05-02)

### Release
- VersionCode: 83
- VersionName: 1.3.6
- APK: 완장봇_v1.3.6.apk
- SHA256: BE5E574E2BAD666369A485C668BE5BD5DDF1886676DE585A32BC4F040AF7EBC4
- Drive file ID: 1ay9xwfDDnjdJ-sAYTgzzpoFVX1HedltH
- Drive link: https://drive.google.com/file/d/1ay9xwfDDnjdJ-sAYTgzzpoFVX1HedltH/view?usp=drivesdk

### 주요 반영
- 1.3.6-beta3 검증 완료 후 정식 릴리즈 전환.
- `차단 예외 글 설정` 추가: 등록한 글 번호의 게시글과 해당 댓글 전체를 모든 차단 검사에서 제외.
- 필터별 개별 차단 설정과 관계없이 작동하도록 차단 후속 동작 섹션의 별도 설정으로 분리.
- 차단 예외 글도 `모든 글 스냅샷`이 켜져 있으면 일반 글처럼 스냅샷을 저장하고 DB snapshotPath를 기록.
- 설정 내보내기/가져오기에 차단 예외 글 번호 목록 포함.

## 1.3.7-beta1

### 주요 변화
- 차단/삭제 요청이 실제 성공했을 때만 완료 로그, 알림, 차단 이력, DB 차단 상태를 남기도록 수정.
- 실패 응답이면 `[차단 실패]` 또는 `[삭제 실패]`와 서버 응답 일부를 로그로 표시.
- 댓글 차단 완료 카운트도 실제 성공한 건만 집계하도록 수정.
- 차단 및 댓글 삭제 API endpoint를 갤러리 타입(일반/마이너/미니)에 맞게 분기.

### 확인 필요
- “인식은 되지만 실제 차단/삭제가 안 됨” 제보에 대한 원인 추적용 베타.
- 실패 시 로그에 나오는 서버 응답을 확인하면 권한/토큰/endpoint 문제를 더 정확히 구분 가능.

## 1.3.7-beta2

### 주요 변화
- 차단/삭제 요청이 실패하면 해당 글의 DB 검사 완료 상태를 갱신하지 않아 다음 사이클에서 다시 시도하도록 수정.
- AI 실행계획 기반 차단/삭제가 실패하면 실행계획을 복원해 다음 사이클에서 재시도할 수 있도록 수정.
- 댓글 삭제 성공 건은 DB 저장 시 댓글 수에서 차감해 불필요한 재검사를 줄이도록 수정.
- 댓글 삭제 후 더 처리할 실패 건이 없고 `전문가 모드 + 모든 글 스냅샷`이 켜져 있으면 최신 댓글 목록으로 일반 스냅샷을 다시 저장하도록 수정.

### 확인 필요
- 실패 시 다음 사이클에서 동일 글 재검사/재시도 여부.
- 댓글 삭제 성공 후 목록 댓글 수와 DB 댓글 수가 맞아 불필요한 반복 상세 접근이 줄어드는지.
- 모든 글 스냅샷 ON 상태에서 댓글 삭제 후 일반 스냅샷이 삭제 후 댓글 목록으로 갱신되는지.

## 1.3.7-beta3 구현 완료

### 주요 변화
- 활동 로그 필터를 단일 선택에서 다중 선택 방식으로 변경
  - `전체` 선택 시 모든 로그 탭 선택
  - `전체` 해제 시 모든 로그 탭 해제
  - 개별 탭 선택 상태가 모두 켜지면 `전체`도 자동 선택 상태로 표시
  - `차단` 탭 명칭을 `처리 내역`으로 변경해 삭제/차단/보류 로그를 함께 표현
- 처리 방식에 `보류` 추가
  - 보류는 삭제/차단 요청을 보내지 않고 알림, 활동 로그, 보류 기록, 스냅샷만 저장
  - 기본 처리 방식과 필터별 개별 처리 방식에서 `삭제` / `차단` / `보류` 선택 가능
- 보류 기록 DB 추가
  - `hold_history` 테이블 추가 및 Room DB version 7 반영
  - 동일 글/댓글 반복 스캔 시 중복 보류 기록/알림 방지
  - DB 대시보드에 주황 톤의 `보류 기록` 탭 추가
- 처리 결과 표기 개선
  - 알림/활동 로그/상세 기록에 실제 처리 방식(`삭제` / `차단` / `보류`)이 드러나도록 정리

### 빌드 정보
- versionCode = 86
- versionName = 1.3.7-beta3
- APK: 완장봇_v1.3.7-beta3.apk
- SHA256: E1A8D54E858DD5FE266C7254ADCD6272F6880DB581078A6BE7F6047FC45B477E
- Beta Drive file ID: 1TxQgJyYc1v4kWV-4eB8I1--A0JL5cqgU

### 정정
- 직전 `1.3.4_beta25` 업로드는 잘못된 워크스페이스에서 생성된 실수 업로드이며, 이번 APK는 실제 `projects/armbandbot` 1.3.7 코드베이스 기준으로 재빌드함

## 1.3.7-beta4 구현 완료

### 주요 변화
- 보류 처리 로그가 `처리 내역` 탭에 표시되도록 로그 분류 규칙 수정
  - `보류`가 포함된 활동 로그를 처리 내역(BLOCK category)으로 분류해 빨간색 로그로 표시
- 활동 로그 탭 선택 상태를 봇별로 저장
  - 저장 키: `activity_log_selected_filters`
  - 뒤로 나갔다가 다시 들어와도 마지막 선택 상태 유지
- 보류 중복 건너뜀 시 엉뚱한 차단/삭제 완료 요약 로그가 나오지 않도록 수정
  - `BlockExecutionResult`에 실제 처리 모드 정보를 포함
  - HOLD 처리/중복 HOLD는 악플 차단 카운트에서 제외
- HOLD 처리 시작 디버그 문구가 `차단요청`이 아니라 `보류처리`로 나오도록 정리

### 빌드 정보
- versionCode = 87
- versionName = 1.3.7-beta4
- APK: 완장봇_v1.3.7-beta4.apk
- SHA256: 1DAF42A70566BF22A9A81B666F926202376D9F61DDF0418A9EE452CC0617E380
- Beta Drive file ID: 1CBGSQDmOmkRXLS9oV75pS8FPzF1hqy1Y

## 1.3.7-beta5 구현 완료

### 주요 변화
- 활동 로그 `처리 내역` 분류 범위를 확장
  - 삭제/차단/보류/처리 성공/처리 실패/완료가 포함된 로그를 처리 내역으로 분류
  - `악플 N개 삭제 및 차단 완료`, `악플 N개 차단 완료` 같은 요약 로그도 처리 내역으로 표시
- AI로 인해 실제 삭제/차단/보류 처리된 로그도 AI 탭이 아니라 처리 내역 탭으로 우선 분류
- 기존 저장 로그(JSON line)에 남아 있던 과거 category 값보다 현재 분류 규칙을 우선 적용하도록 변경
  - 업데이트 후 기존 로그도 새 분류 기준으로 다시 보임

### 빌드 정보
- versionCode = 88
- versionName = 1.3.7-beta5
- APK: 완장봇_v1.3.7-beta5.apk
- SHA256: A68A2F75F81D25A24B44D36D9F48E213F461BBC51F52F7113975A6B4F215EB38
- Beta Drive file ID: 1q2WW8rBZrCD6NFqPU5JK4haOppxYACYv

## 1.3.7-beta6 구현 완료

### 주요 변화
- beta5에서 과하게 넓힌 활동 로그 처리 내역 분류 조건을 축소
  - `완료`, `처리 성공`, `처리 실패`, `처리됨` 같은 일반 조건은 제거
  - `삭제` / `차단` / `보류`가 명시된 로그만 처리 내역으로 분류
- AI 처리 로그도 실제 삭제/차단/보류가 명시된 경우에만 처리 내역으로 분류
- 기존 저장 로그는 계속 현재 분류 규칙으로 재분류되므로, beta5에서 잘못 처리 내역으로 보이던 일반 완료 로그는 다시 원래 탭으로 돌아감

### 빌드 정보
- versionCode = 89
- versionName = 1.3.7-beta6
- APK: 완장봇_v1.3.7-beta6.apk
- SHA256: A3DD07CD3C96EF7337A99121A7E325EA409FD0312928A53FC0959ED93B545B8F
- Beta Drive file ID: 1-gHRziAs0sPMDTpejoHOIpPv0kyd_NP6

## 1.3.7 정식 릴리즈 완료

### 주요 변화
- 처리 방식 `보류` 정식 반영
  - 삭제/차단 없이 알림, 활동 로그, 보류 기록, 스냅샷만 남김
  - 동일 글/댓글의 보류 알림·기록 중복 생성 방지
  - DB 대시보드에 주황 톤의 `보류 기록` 탭 추가
- 활동 로그 필터 개선
  - 다중 선택 지원
  - `전체` 선택/해제 연동
  - 봇별 활동 로그 탭 선택 상태 저장
  - 삭제/차단/보류 관련 로그는 `처리 내역` 탭으로 분류
- 처리 결과 표기 개선
  - 알림, 활동 로그, DB 기록에 실제 처리 방식(`삭제`/`차단`/`보류`) 표시
  - 보류 중복 건너뜀 시 차단/삭제 완료 요약이 나오지 않도록 수정

### 릴리즈 정보
- versionCode = 90
- versionName = 1.3.7
- APK: 완장봇_v1.3.7.apk
- SHA256: 27E9BFF40EA21703E93596970F39A5A6A9C58891FC3A7E75CE1E90CACF078C8A
- Release Drive file ID: 1P7Qm-mUDwGQANN47-deo4C32bNIWhc5K
- Release Drive folder ID: 1zyy7Yl8nmAge83JOaFBONm438432rxA3
- Google Docs 매뉴얼/패치노트 및 처음 사용자 가이드에 1.3.7 내용 반영

## 1.3.8-beta1 배포 완료

### 주요 변화
- 처리 실패 로그의 DC 응답 JSON을 사람이 읽을 수 있게 정리
  - `msg`/`message` 값을 파싱해 `\uc2dc...` 형태 대신 실제 한글 메시지로 표시
  - 실패 로그에 적용 정책(`ai_override`, `url_override` 등)을 함께 표시해 AI/URL/키워드 경로를 구분 가능
- 유효하지 않은 댓글 번호 방어 추가
  - 댓글 번호가 비어 있거나 `0`이면 차단/삭제 API 호출을 하지 않고 건너뜀
  - `댓글 번호: 0` 대상이 매 사이클 DC 서버 오류를 반복시키는 문제를 차단
- 반복 실패 재시도 억제 추가
  - 같은 봇/갤러리/게시글/대상/처리모드/정책 조합이 실패하면 10분간 동일 요청을 건너뜀
  - 일시적인 DC 서버 오류나 잘못된 대상 번호가 활동 로그를 계속 도배하지 않도록 완화
- 봇 정지 시 최근 처리 실패 억제 상태도 함께 정리

### 배포 정보
- versionCode = 91
- versionName = 1.3.8-beta1
- APK: 완장봇_v1.3.8-beta1.apk
- SHA256: 76FCF809FFD1569A2F1C6F30923AAA6AA8AE60102455FAF8E5C414AC10682411
- Beta Drive file ID: 1PMCVeTaDfshyWLOUUCEuhcnGnKIo5Kxw

## 1.3.8-beta2 배포 완료

### 주요 변화
- 디시 자체 광고/시스템 댓글인 댓글돌이를 전역 검사 대상에서 제외
  - `nicktype=COMMENT_BOY`, `no=0`, `gallog_icon`의 `cmtboy`, 작성자/시간 정보가 비어 있는 `reply_w=N` 시스템 댓글을 제외
  - 닉네임 `댓글돌이` 단독 조건은 사용하지 않음
- 댓글 API 응답을 받은 직후 시스템 댓글을 필터링해 이후 경로가 정상 댓글만 사용하도록 정리
  - AI 필터 입력
  - URL/금지어/유저/닉네임 등 일반 댓글 필터
  - 보류/삭제/차단 처리 후보
- 스냅샷 저장 댓글 목록에서도 댓글돌이를 제외
  - 전체 스냅샷
  - 댓글 삭제 반영 재저장 스냅샷
  - 댓글 차단 증거 스냅샷 재조회 경로
- 디버그 모드에서는 제외된 시스템 댓글 수와 실제 검사 대상 댓글 수를 표시
- beta1의 안전망 유지
  - 유효하지 않은 댓글 번호 API 호출 방지
  - 실패 응답 한글 표시
  - 동일 실패 대상 10분 재시도 억제

### 배포 정보
- versionCode = 92
- versionName = 1.3.8-beta2
- APK: 완장봇_v1.3.8-beta2.apk
- SHA256: C47FA55DD785ED461076E161F76AA499EC91AC31118223843A58A160A02977C8
- Beta Drive file ID: 19Zcz-dz2qx9vnI45AcRI4Kd66X08iQdd

## 1.3.8-beta3 배포 완료

### 주요 변화
- AI 필터 처리 알림을 실제 처리 방식에 맞춰 표시
  - 차단: `AI 필터 차단 처리됨` / `AI 필터로 게시글 또는 댓글을 차단 처리했습니다.`
  - 삭제: `AI 필터 삭제 처리됨` / `AI 필터로 게시글 또는 댓글을 삭제 처리했습니다.`
  - 보류: `AI 필터 보류 처리됨` / `AI 필터로 게시글 또는 댓글을 보류 처리했습니다.`
- AI 필터로 실제 삭제/차단/보류 처리된 활동 로그가 AI 탭이 아니라 처리 내역 탭으로 분류되도록 로그 카테고리 문구를 처리 방식 포함 형태로 변경
- 기존 댓글돌이 전역 제외, 댓글 번호 0 안전망, 실패 응답 한글 표시, 동일 실패 재시도 억제는 유지

### 배포 정보
- versionCode = 93
- versionName = 1.3.8-beta3
- APK: 완장봇_v1.3.8-beta3.apk
- SHA256: B52D0F617063AA1C34E0C769CC53C394A1956249D7AEAECE6ACF093A686A4566
- Beta Drive file ID: 1eXx9CoFAZULI9gtPgMCOZzV8B8QcwNGR

