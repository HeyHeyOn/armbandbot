## 1.5.4-beta3

- 모바일 주소도 같은 방식으로 인식하고, 한 봇의 여러 갤러리에 자동 탭 분류 규칙을 따로 설정할 수 있습니다.
- 각 필터의 연필 왼쪽 구름 버튼에서 원격 목록을 따로 연결합니다. 목록별로 주소·유형·사용 여부를 정하고, 구분 열 없이 단순 문자열 목록을 불러옵니다.
- 예약 끌올 시각을 직접 입력하는 대신 시계 선택창에서 추가·변경합니다.
- [APK 다운로드](https://drive.google.com/file/d/1MPb2VZimN8869KOGt1MfANtWdqKKNsWX/view) · [사용 안내](https://drive.google.com/file/d/181VXnNdqmWvottHSPBCD46IfGqy8LdGt/view)

## 1.5.2-beta3 배포 완료

- 독립 검사 기록·작동 시간대 설정을 주변 설정과 같은 카드·스위치 형태로 정리했습니다.
- 검사 목록의 깔때기 버튼에서 갤러리·검사 범위를 함께 선택하고 적용합니다. 취소·뒤로가기·창 밖 터치는 기존 선택을 유지합니다.
- 기본 전체 범위 요약과 공용 행의 범위 배지를 숨기고, 범위를 좁힌 경우에만 요약을 표시합니다. 새 깔때기·필터 창은 앱의 밝은/어두운 테마에 맞춰 읽기 쉽게 수정했습니다.
- 초기화는 갤러리·검색·체크 행과 무관하게 적용된 검사 범위 전체를 지웁니다. 검사 범위가 전체이면 조치 이력까지 삭제하므로 먼저 DB를 백업하세요.
- [APK 다운로드](https://drive.google.com/file/d/1fuXQbxIpQO5EoHE5vuympZ6fX_nTkb5J/view) · [사용 안내](https://drive.google.com/file/d/1mQSvndMH1o3Qk_CLKcMb3aGzPUkPi8Jr/view)
- JVM 603개, API 24 40개·API 35 42개 통과(실패·오류·건너뜀 0). API 24는 API 26 이상 픽셀 검사 2개가 대상에서 제외됩니다. lintRelease 오류 0·경고 73, 서명·DEX 검사 통과.
- 최종 APK의 양 API 밝은/어두운 필터·설정 화면을 확인했습니다. 오프라인 합성 데이터 검증이며 실제 계정 연동·삭제·차단·장시간 반복 운용 및 측정된 접근성 준수를 보장하지 않습니다.
- versionCode 172 / versionName 1.5.2. APK·안내문 Drive 메타데이터 및 재다운로드 SHA-256 일치 확인.
- 소스 커밋: `093d141aae3eaabcbad6bcc2986d49113eaa47a3`
- APK SHA-256: `4460c3dcf81b2dea1ccc76f6bc3833f5009bcebc9d431ef2a8407dbb217d4329`
- 검증 근거: `releases/1.5.2-beta3-verification.json`. 정식 Google Docs는 수정하지 않았습니다.

## 1.5.2-beta2 배포 완료

- 한 봇에 여러 작동 시간대를 추가·편집·삭제할 수 있습니다. 야간 구간과 떨어진 구간을 함께 지정하고, 옵션을 꺼도 목록을 유지합니다.
- 갤러리 선택 아래에서 전체 / 공용 / 봇별 독립 검사 기록을 전환합니다. 범위 변경 중 오래된 검색 결과가 새 화면을 덮지 않도록 보강했습니다.
- 범위 초기화는 선택한 검사 범위 전체에 적용됩니다. 전체 탭의 초기화는 조치 이력까지 삭제하므로 먼저 백업하세요.
- [사용 안내](https://drive.google.com/file/d/1FnBj5h1Spf5Po7WyOZxFbEVvvu8r2_lE/view) · [APK 다운로드](https://drive.google.com/file/d/1ALI5Wg8up-rJOFZFUNyXAH-TGvH9Ii1H/view)
- JVM 596개, API 24·35 계측 각각 31개 통과(실패·오류·건너뜀 0). lintRelease·서명·DEX 검사 통과.
- 최종 APK로 양 API에서 독립 기록 탭과 시간대 편집·동일 시각 거부·재진입 보존을 확인했습니다. 다크 DB 일부 비선택 글자는 대비가 약하며 접근성 기준 충족을 측정하지 않았습니다.
- versionCode 171 / versionName 1.5.2. APK·안내문 Drive 메타데이터와 재다운로드 SHA-256 일치 확인.
- 소스 커밋: `bc85909e9794bd6a02f6b894e7fd309cd8596cab`
- APK SHA-256: `a391c4845a74fa318ce80b30aa118aa0e9a4811f1a6c3db9c2f07748c66c9ab2`
- 검증 근거: `releases/1.5.2-beta2-verification.json`. 이번 최종 검증은 오프라인 UI/자동 테스트이며 실제 계정 연동·장시간 운용 검증은 아닙니다.

# 완장봇 1.5.2-beta1

## 개발용 베타 배포 완료
- 봇별 독립 검사 기록과 매일 작동 시간대 설정을 추가했습니다.
- [사용 안내](../docs/independent-records-and-schedules.md)

### 검증 및 배포
- JVM 테스트 564개, API 24·35 계측 테스트 각각 19개 통과.
- lint 오류 0개, release 빌드·서명·DEX 한도와 양 기기의 봇 상세 화면 진입 확인.
- APK와 사용 안내문을 개발용 Drive 폴더에 업로드하고 재다운로드 SHA-256 일치를 확인했습니다.
- [APK 다운로드](https://drive.google.com/file/d/1nqhqyg0b3VojIT8zrqupqzg1gJjvscle/view?usp=drivesdk) · [사용 안내](https://drive.google.com/file/d/1RkrPZ5iYFFNcp7rDs_qpphn8G0gFWzCK/view?usp=drivesdk)
- 소스 커밋: `a58fab64befcb3ad5259a7ca6689380ff7ff1252`
- APK SHA-256: `106c57502fd798f3ad6cfc0c407f2bf53ed26c1aba63f4275982f085e65b2262`
- 실제 DC 로그인·삭제·차단 및 장시간 운용은 이번 자동 검증에 포함하지 않았습니다.

---

# 완장봇 1.5.1

## 1.5.1 정식 배포 완료

### 변경 사항
- 휴대폰 재부팅, 앱 업데이트 또는 Android가 앱을 종료한 뒤에도 실행 중이던 봇의 자동 복구를 시도하고, 제한된 경우 알림에서 다시 시작할 수 있도록 개선했습니다.
- 유동·깡계의 디시 동영상 첨부 게시글 차단 설정을 추가했습니다.
- DB 대시보드에서 글과 댓글의 작성자·내용까지 검색하고 검색 범위와 일치 항목을 확인할 수 있도록 개선했습니다.
- 여러 줄 설정 입력창의 사용성과 저장·복사·백업·실행 시 입력 순서 보존을 개선했습니다.
- 스냅샷 안의 디시 동영상을 재생할 수 있게 하고, 구버전 최초 스냅샷이 첫 재검사에서 덮어써지던 문제를 수정했습니다.
- Groq의 중단된 기본 모델을 현재 지원 모델로 변경했습니다.

### 검증
- Clean debug/release 단위 테스트 각각 413개 통과
- `lintDebug`, `lintVitalRelease`, `assembleRelease` 통과
- APK manifest `versionName 1.5.1`, `versionCode 169` 확인
- APK v2 서명 및 기존 인증서 SHA-256 일치 확인
- DEX method IDs `65,349 / 65,125 / 10,675`, 최소 여유 187
- DEX 최대 입력 `248/255`, 보수적 여유 7워드
- Room DB 버전 8 유지, 파괴적 마이그레이션 없음
- 매뉴얼·패치노트·처음 사용 가이드 갱신 후 탭·스타일·필수 문구 재검증

### 배포 정보
- versionName: `1.5.1`
- versionCode: `169`
- APK: `완장봇_v1.5.1.apk`
- 크기: `13,010,990 bytes`
- SHA-256: `2b7c5042cb280ecb04f8e8877c2645e430f16d01bc42b0062101e4b4a016bcb6`
- Drive 파일 ID: `1vcr8tkkjInDY8KNIBMo31MZ3mhOUs0G-`
- Drive 링크: https://drive.google.com/file/d/1vcr8tkkjInDY8KNIBMo31MZ3mhOUs0G-/view?usp=drivesdk
- 업로드 후 재다운로드 SHA-256 일치 확인

---

# 완장봇 1.5.0-beta8

## 1.5.0-beta8 배포 완료

### 수정 사항
- 펌 필터에 상세 설정 화면을 추가해 `펌 게시글 모두 차단`, `매 검사 주기마다 원문 재검사`, 펌 전용 처리 방식을 각각 설정할 수 있습니다.
- `펌 게시글 모두 차단`은 실제 구조가 확인된 펌 글에만 적용하며, 제목에 `(펌)`을 직접 적은 일반 글은 전체 차단 대상으로 오인하지 않습니다.
- 펌 전용 처리에서는 차단·삭제·보류 방식, 차단 시간, 게시글 삭제 여부와 차단 사유를 별도로 지정할 수 있습니다.
- 바깥 펌 글 자체의 위반, 펌 원문에서만 발견된 위반, AI 판정을 구분해 각 상황에 맞는 처리 설정을 적용합니다.
- 펌 원문에서 위반이 발견되어도 제재 대상은 원문 작성자가 아니라 현재 검사 중인 바깥 펌 글과 작성자로 유지합니다.
- 펌 게시글 모두 차단이 활성화된 경우 불필요한 원문 요청을 생략해 검사와 스냅샷 저장 과정의 네트워크 사용을 줄입니다.
- 기존 펌 필터 사용 여부와 하위 설정값은 유지되며, 마스터 설정을 껐다 다시 켜도 상세 설정이 초기화되지 않습니다.

### 검증
- debug/release 단위 테스트 각각 256개 통과
- `lintDebug` 오류 0
- `assembleRelease` 및 APK v2 서명 검증 통과
- DEX method IDs `65,349 / 64,437 / 10,675`
- DEX 최대 입력 `247/255` — 여유 8워드
- 펌 판정·처리 대상·설정 가져오기·스냅샷 원문 요청 억제 회귀 테스트 통과
- 최종 독립 보안·정확성 리뷰 승인
- Room DB 버전 8 유지, 마이그레이션 없음

### 배포 정보
- versionName: `1.5.0`
- versionCode: `156`
- APK: `완장봇_v1.5.0-beta8.apk`
- 크기: `12,945,102 bytes`
- SHA-256: `32bd51ee27d2e693e7c4121c19b8e4c1f542bd2a73dbdfcd38547a036dde4ffc`
- Drive 파일 ID: `167ogqcNmgzPNodl0xpd0j-un92LzOA15`
- Drive 링크: https://drive.google.com/file/d/167ogqcNmgzPNodl0xpd0j-un92LzOA15/view?usp=drivesdk
- 업로드 후 재다운로드 SHA-256 일치 확인

---

# 완장봇 1.5.0-beta7

## 1.5.0-beta7 배포 완료

### 수정 사항
- 저장 성공 로그는 한 번만 표시되도록 정리하되, 일반 스냅샷과 차단 증거 스냅샷의 기존 저장 생명주기는 그대로 유지합니다.
- DCInside 원본 스냅샷에서 추천·비추천·실베추·펌·공유·스크랩·신고 버튼의 실제 DOM과 외형을 보존합니다.
- 보존 버튼은 종류별 한 개씩 정확히 7개만 남기고 실행 속성·중첩 링크·원격 미디어를 제거해 정적으로 표시합니다.
- 게시글 본문과 이미지는 유지하면서 움직이는 강아지 장식과 해당 전용 스타일만 제거합니다.

### 검증
- 첨부된 실제 `laboratory1/2317` 원본 HTML에서 버튼 7개, 펌 카드, 본문, 댓글, 원본 DC 구조 보존 확인
- debug/release 단위 테스트 각각 208개 통과
- `lintDebug` 오류 0
- `assembleRelease` 및 APK v2 서명 검증 통과
- DEX `invoke-*/range` 최대 입력 `246/255` — 여유 9워드
- 적대적 중복 버튼·위조 추천 박스·중첩 실행 요소 회귀 테스트 통과
- 최종 독립 보안·정확성 리뷰 승인
- Room DB 버전 8 유지, 마이그레이션 없음

### 배포 정보
- versionName: `1.5.0`
- versionCode: `155`
- APK: `완장봇_v1.5.0-beta7.apk`
- 크기: `12,912,334 bytes`
- SHA-256: `6510f89c07d6f419bfc06cae9a8e52b267f950355d3897a037ef9e41176fac98`
- Drive 파일 ID: `1aOcDxe1S4sF71kFijDilx1BGgddExD9N`
- Drive 링크: https://drive.google.com/file/d/1aOcDxe1S4sF71kFijDilx1BGgddExD9N/view?usp=drivesdk
- 업로드 후 재다운로드 SHA-256 일치 확인

---

# 완장봇 1.5.0-beta4

## 1.5.0-beta4 배포 완료

### 수정 사항
- 실제 DCInside 펌 로더가 익명 즉시실행 함수(IIFE) 안에서 동작하는 형식을 정적으로 해석합니다.
- `https:\/\/...`처럼 JavaScript에서 슬래시를 이스케이프한 로더 URL을 안전하게 복원한 뒤, 기존의 정확한 HTTPS 호스트·경로 검증을 그대로 적용합니다.
- 상세 페이지 제목의 구조적 `(펌)` 표식을 인식해 목록 표식이 전달되지 않은 경우에도 펌 글을 놓치지 않습니다.
- 원문 로더 해석에 실패하면 스냅샷에 빈 본문 대신 명시적인 실패 카드를 남깁니다.
- 호출되지 않은 함수나 조건부 블록 안의 IIFE는 실행 가능한 로더로 취급하지 않습니다.

### 실제 사례 검증
- 첨부 스냅샷의 바깥 글 `laboratory1/2316`을 기준으로 실제 카드 API 응답을 확인했습니다.
- 원문 후보는 `laboratory1/2315`로 확인했으며, 갤러리 목록·댓글 앵커 링크를 제외하고 카드 본문의 직접 원문 링크만 선택합니다.
- JavaScript는 실행하지 않고 제한된 문자열·객체 리터럴만 정적으로 해석합니다.

### 검증
- debug/release 단위 테스트 각각 199개 통과
- `lintDebug` 오류 0
- `assembleRelease` 및 APK v2 서명 검증 통과
- DEX `invoke-*/range` 최대 입력 `246/255` — 여유 9워드
- 독립 보안·정확성 리뷰 승인
- Room DB 버전 8 유지, 마이그레이션 없음

### 배포 정보
- versionName: `1.5.0`
- versionCode: `153`
- APK: `완장봇_v1.5.0-beta4.apk`
- 크기: `12,912,334 bytes`
- SHA-256: `904f27e58df20fe2159cf288e6945e5e70b4e0f4ab30b84e1d5fd31f6085d525`
- Drive 파일 ID: `1rJAZuhxhsuk-8aiy5eeP1iu8tJ0i5miv`
- Drive 링크: https://drive.google.com/file/d/1rJAZuhxhsuk-8aiy5eeP1iu8tJ0i5miv/view?usp=drivesdk
- 업로드 후 재다운로드 SHA-256 일치 확인

---

# 완장봇 1.5.0-beta3

## 1.5.0-beta3 배포 완료

### 수정 사항
- 실사이트 목록에서 제목 링크 옆의 `<b class="font_blue009">(펌)</b>` 형태도 펌 글로 정확히 인식합니다.
- 펌 로더가 URL과 데이터 객체를 변수로 분리하거나 글번호를 숫자로 전달하고, 갤러리 종류를 `null` 또는 빈 값으로 전달하는 형태를 안전하게 해석합니다.
- 로더의 원문 힌트와 실제 바깥 펌 글의 식별자를 분리했습니다. 전용 클래스가 없는 원문 링크는 로더 힌트와 정확히 일치할 때만 허용합니다.
- 스냅샷에서 DCInside 스타일시트와 페이지 CSS는 보존하면서 스크립트·이벤트 속성·폼·iframe·위험 URL 등 실행 가능한 요소를 제거합니다.
- 펌 로더 해석에 실패해도 빈 스냅샷 대신 `원문을 불러오지 못했습니다` 카드를 저장합니다.
- 전체 스냅샷 옵션이 켜져 있는데 DB가 가리키는 실제 파일이 없으면, 제목·댓글 수가 같아도 해당 글을 다시 검사해 스냅샷을 복구합니다.
- beta2의 구버전 최초 스냅샷 승계·보존 로직은 그대로 유지합니다.

### 검증
- debug/release 단위 테스트 각각 192개 통과
- `lintDebug` 오류 0
- `assembleRelease` 및 APK v2 서명 검증 통과
- DEX `invoke-*/range` 최대 입력 `246/255` — 여유 9워드
- 공격형 JavaScript 변수·원문 링크·스냅샷 활성 콘텐츠 회귀 테스트 통과
- 독립 보안·정확성 리뷰 승인
- Room DB 버전 8 유지, 마이그레이션 없음

### 배포 정보
- versionName: `1.5.0`
- versionCode: `152`
- APK: `완장봇_v1.5.0-beta3.apk`
- 크기: `12,912,334 bytes`
- SHA-256: `f453d19af92c5162078055ce15c0cae08a05ff88aceee728502f46ecddc152b9`
- Drive 파일 ID: `1f3qY7ydqJC-xZZ9gS03z0ZfL3HZtJKNd`
- Drive 링크: https://drive.google.com/file/d/1f3qY7ydqJC-xZZ9gS03z0ZfL3HZtJKNd/view?usp=drivesdk
- 업로드 후 재다운로드 SHA-256 일치 확인

---

# 완장봇 1.5.0-beta2

## 1.5.0-beta2 배포 완료

### 수정 사항
- Android 실기기에서 정상 스냅샷 경로를 심볼릭 링크로 잘못 판정해 전체 스냅샷 저장이 실패하던 문제를 수정했습니다.
- 앱이 신뢰한 캐시 루트와 Android 시스템 상위 경로는 검사 대상에서 제외하고, 캐시 루트 아래의 실제 스냅샷 경로 구성요소만 심볼릭 링크 여부를 검사합니다.
- 실제 하위 심볼릭 링크·끊어진 링크·신뢰 경로 이탈은 기존처럼 차단하며, 최초 스냅샷 보존 및 최신 스냅샷 교체 안전장치를 유지합니다.

### 검증
- 실기기 로그의 `Snapshot target path contains a symbolic link` 실패 조건을 회귀 테스트로 재현한 뒤 수정
- `SnapshotRecoveryTest` 22개 통과
- debug/release 단위 테스트 각각 183개 통과
- `lintDebug` 오류 0
- `assembleRelease` 및 APK v2 서명 검증 통과
- DEX `invoke-*/range` 최대 입력 `246/255` — 여유 9워드
- 독립 보안·정확성 리뷰 승인

### 배포 정보
- versionName: `1.5.0`
- versionCode: `151`
- APK: `완장봇_v1.5.0-beta2.apk`
- 크기: `12,912,334 bytes`
- SHA-256: `9e554dfcd1aebbbb43453b77760a28d779b369a23b630612d8194230bf2f3d2c`
- Drive 파일 ID: `1S9WfTJfly7QZ2jLWS3qkdqv0_DhORcb4`
- Drive 링크: https://drive.google.com/file/d/1S9WfTJfly7QZ2jLWS3qkdqv0_DhORcb4/view?usp=drivesdk
- 업로드 후 재다운로드 SHA-256 일치 확인

---

# 완장봇 1.5.0-beta1

## 1.5.0-beta1 배포 완료

### 주요 변화
- DCInside 펌 게시물의 구조적 표식을 인식하고, 안전하게 확인된 원문의 제목·본문·이미지 대체텍스트·링크·미디어를 기존 규칙 필터와 AI 필터에서 함께 검사합니다.
- `펌 원문 내용 검사`와 `펌 글을 매 사이클마다 검사` 옵션을 추가했습니다. 두 옵션은 기존 사용자와 새 봇 모두 기본적으로 꺼져 있습니다.
- 매사이클 재검사는 현재 설정된 목록 범위에 보이는 펌 글에만 적용하며, 같은 사이클의 동일 원문 요청은 한 번으로 줄입니다.
- 펌 원문 확인 실패 시 원글의 기존 검사를 계속하고, 확인되지 않은 원문 때문에 글을 삭제하지 않습니다.
- 최초/최신 스냅샷에 정적으로 정제된 펌 원문 카드를 저장하고, 앱 뷰어에서 접기·펼치기로 확인할 수 있습니다.
- 1.4.4 및 이전 버전에서 저장한 최초 스냅샷이 첫 재검사 결과로 교체되는 문제를 수정했습니다. 기존 최초 기준은 보존하고 이후 결과만 최신 스냅샷으로 기록합니다.
- AI 배치 결과를 갤러리 종류·갤러리 ID·글번호의 복합 식별자로 구분하고, 일부 누락·중복·오래된 응답의 오집행 및 실패 계획 유실을 방지했습니다.

### 동작 범위
- `펌 원문 내용 검사`가 꺼져 있으면 펌 원문 네트워크 요청을 하지 않습니다.
- `펌 글을 매 사이클마다 검사`는 부모 옵션이 켜진 경우에만 사용할 수 있습니다.
- 목록 밖으로 밀린 펌 글은 별도로 추적하지 않습니다.
- 스냅샷의 펌 미디어는 안전한 DCInside HTTPS 이미지 주소만 앱에서 표시합니다.

### 검증
- `testDebugUnitTest`, `testReleaseUnitTest` 통과
- `lintDebug` 오류 0
- `assembleRelease` 통과 및 APK v2 서명 검증
- DEX `invoke-*/range` 최대 입력 `246/255` — 여유 9워드
- 독립 사양·보안·통합 리뷰 승인
- Room DB 버전 8 유지, 파괴적 마이그레이션 없음

### 배포 정보
- versionName: `1.5.0`
- versionCode: `150`
- APK: `완장봇_v1.5.0-beta1.apk`
- 크기: `12,895,950 bytes`
- SHA-256: `7de6f77fabafc89ceabaea35425f42a6b74a56a394c16d64d83253bcd1f65f2f`
- Drive 파일 ID: `1Js_SrDlG0Uph6PnfzBHxhiuXH64kcXqB`
- Drive 링크: https://drive.google.com/file/d/1Js_SrDlG0Uph6PnfzBHxhiuXH64kcXqB/view?usp=drivesdk
- 업로드 후 재다운로드 SHA-256 일치 확인

---

# 완장봇 1.4.5-beta4

## 변경사항
- beta3에서 봇 목록의 봇을 눌러 상세 화면으로 이동할 때 앱이 강제 종료되던 문제를 수정했습니다.
- 금지어 설정 UI를 작은 화면 구성요소로 분리해 Android DEX 호출 인자 한계를 넘지 않도록 구조를 개선했습니다.
- 릴리즈 APK의 모든 DEX 메서드 입력 수를 검사하는 자동 회귀 검증을 추가했습니다.
- 우회 금지어 설정의 `영문 대소문자 우회 감지`와 `유니코드 문자 우회 감지` 기능을 유지합니다.
- 복원한 구버전 최초 스냅샷이 첫 재검사 결과로 교체되지 않도록 기존 최초 파일을 현재 봇 저장 경로로 승계합니다.

## 참고
- 새 우회 감지 옵션은 기존 사용자와 새 봇 모두 기본적으로 꺼져 있습니다.
- `영문 대소문자 우회 감지`를 켜면 등록한 영문 금지어의 대문자와 소문자를 같은 문자로 검사합니다.
- `유니코드 문자 우회 감지`를 켜면 전각 문자, 보이지 않는 문자와 일부 비슷하게 생긴 외국 문자를 정규화해 검사합니다.
