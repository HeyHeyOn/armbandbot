# 1.6.0-beta1 — 자동 탭 분류 권한 판정

## 기준과 범위

- 직전 배포는 `1.5.4-beta3`, code179, `b730f621dc111cb945652109b1b8ef8b3d957955`이다. 사용자가 언급한 1.5.3 beta3와 실제 원장을 대조한 뒤 이 이력을 그대로 이어갔다.
- 새 버전은 `1.6.0-beta1`, code180. 이전 베타의 예약 끌올·자동 탭 분류·독립 원격 목록 기능과 저장 데이터를 유지한다.
- 이번 변경은 자동 탭 분류 설정의 권한 확인이다. 게시글 조치 실행기, DB schema, 로그인 구현은 변경하지 않는다.

## 진단

`loadManagedGalleryTabs`는 저장된 로그인 쿠키로 목록을 가져온 뒤 `automationManagerConfirmed`가 false이면 탭을 표시하지 않는다. 기존 판정은 `listSearchHead(999)`와 일부 `manager` 링크에 의존했다.

실제 laboratory1의 인증/비인증 응답을 비교해 관리자에게만 제공되는 다음 구조를 확인했다.

- `.btn_useradmin_go`의 `/mgallery/management?id=...` 이동 버튼. 기존 selector/문자열 `manager`는 `management` 버튼을 인식하지 못한다.
- `script#minor_buttons-tmpl` 안의 `.mng_subject_sel` 및 `javascript:chg_headtext_batch(...)` 링크. 탭 추출기는 이 template을 읽지만 기존 권한 판정기는 읽지 않는다.
- 비인증 페이지에는 공개 탭 링크가 있지만 위 관리 버튼과 template은 없다.

현재 시험 계정의 전체 목록에는 기존 코드도 인식하는 999 링크가 함께 있어 원래 오류가 그대로 재현되지는 않았다. 실제 관찰한 관리 버튼/template을 독립 fixture로 검증하고, 선택적 매니저 링크가 없는 경우와 따옴표/공백 변형을 회귀 테스트로 재현했다. 기존 코드에서 새 양성 테스트 3건이 실패하고 수정 후 통과했다. 제보한 갤러리 주소·역할은 별도로 제공되지 않았다.

## 수정

- 마이너/미니 관리 이동 버튼을 정확한 클래스와 관리 경로로 확인한다.
- 기존 allowlist에 있는 관리 template의 실제 말머리 변경 링크를 권한 증거로 인식한다.
- 매니저 탭 호출의 숫자/따옴표와 JavaScript 공백 차이를 허용한다.
- 공개 탭 목록, CSRF 토큰, 공개 관리 내역 버튼, 무관한 script는 권한 근거로 추가하지 않는다.

## 검증과 배포 기록

- `AutomationPermissionTest`: 기존 증거 유지, 새 권한 구조, 비권한 구조 회귀.
- `ManagedGalleryTabsLiveTest`: 실제 저장 세션으로 탭 조회→비활성 규칙 저장, 비로그인 공개 탭 거부. 서버 변경 요청 없이 GET만 사용한다.
- 최종 실행 결과, 정확한 APK SHA256, 서명, DEX, API별 화면/업데이트, Drive 재다운로드는 `releases/완장봇_v1.6.0-beta1_검증.json`에 기록한다.
- 이번 검증은 실제 게시물 이동/끌올·장시간 운용을 재실행하지 않는다. 관련 기존 회귀 suite는 계속 실행한다.
