import json
from pathlib import Path
import requests

TOKEN = json.loads(Path(r'C:\Users\Administrator\.config\gws\token_cache.json').read_text(encoding='utf-8'))['access_token']
DOC_ID = '1AHVTh0MBFlqyCkFmPma0uYkU5k4g-hMTcOMmh6LGeGU'
BASE_GET = f'https://docs.googleapis.com/v1/documents/{DOC_ID}?includeTabsContent=true'
BASE_POST = f'https://docs.googleapis.com/v1/documents/{DOC_ID}:batchUpdate'
headers = {'Authorization': f'Bearer {TOKEN}', 'Content-Type': 'application/json'}

doc = requests.get(BASE_GET, headers={'Authorization': f'Bearer {TOKEN}'}, timeout=120).json()
patch_tab = next(tab for tab in doc['tabs'] if tab.get('tabProperties', {}).get('title') == '패치노트')
body = patch_tab['documentTab']['body']['content']
end_index = body[-1]['endIndex'] - 1

text = """v1.3.2 (2026-04-22)
* 마이너 갤러리에서 글 삭제가 더 잘 되도록 수정했습니다.
 - 도배 방지로 글을 지울 때, 마이너/미니 갤러리에서 더 안정적으로 작동하도록 다듬었습니다.
* 도배 감지가 더 자연스럽게 되도록 보완했습니다.
 - 일반 이용자 글이 중간에 섞여 있어도, 유동/깡계 글 기준으로 도배를 더 정확하게 감지하도록 개선했습니다.
* 유동 글과 깡계 글 구분을 더 정확하게 다듬었습니다.
 - 유동 글이 깡계로 잘못 보이면서 엉뚱하게 처리될 수 있던 부분을 줄였습니다.
* 확인용 로그를 조금 더 보기 쉽게 정리했습니다.
 - 디버그 모드에서 삭제 관련 흐름을 확인하기 쉽도록 보완했습니다.

v1.3.1 (2026-04-21)
* 도배 방지 기능 추가
 - 일정 빈도 이상으로 유동/깡계 글이 올라오면 도배 방지 모드를 작동하여 새로 올라오는 유동/깡계 글을 자동 삭제합니다.
* 개념글/말머리 주소 인식 지원
 - 이제 개념글 목록, 특정 말머리 목록 주소를 구분하여 감지합니다. 개념글 페이지나 특정 말머리 페이지를 별도로 관리할 수 있습니다.
* 기타
 - 꺼진 필터는 검사에 사용하지 않도록 패치하여 렉을 줄였습니다.
 - 디버그 로그에 성능 로그를 추가해 상세 fetch, 댓글 fetch, 스냅샷 저장, 게시글 전체 처리 시간을 확인할 수 있도록 했습니다.

v1.2.2 (2026-04-15)
* 전체 스냅샷(‘모두’) 저장 경로 보강
 - 차단 스냅샷은 정상인데 전체 스냅샷만 ‘스냅샷이 없음’으로 보일 수 있던 문제를 정리했습니다.
 - 전체 스냅샷 저장 후 반환 경로를 DB snapshotPath에 반영하도록 수정했습니다.
 - 저장 성공/실패 여부를 활동 로그에서 더 쉽게 확인할 수 있도록 보강했습니다.

v1.2.1 (2026-04-10)
* 갤러리 주소 인식 로직 긴급 보강
 - ‘id=’ 파라미터가 없는 주소도 host/path 규칙을 기반으로 갤러리로 인식하도록 수정했습니다.
 - 마이너 갤러리: ‘gall.dcinside.com/갤러리아이디’, ‘m.dcinside.com/board/갤러리아이디’ 형식을 지원합니다.
 - 미니 갤러리: ‘gall.dcinside.com/mini/갤러리아이디’, ‘m.dcinside.com/mini/갤러리아이디’ 형식을 지원합니다.
* 서로 다른 주소 형식을 입력해도 내부적으로 동일한 갤러리 목록 주소 기준으로 처리되도록 정리했습니다.

v1.2.0 (2026-04-09)
* AI 필터 기능 추가
 - 클라우드 기반 AI 판별 흐름 도입
 - 게시글/댓글 배치 판별 구조 적용
 - AI 차단 기록, 스냅샷, 활동 로그 흐름 정리
 - 활동 로그 UI 및 분류/내보내기 개선
* 자동 로그인 흐름 정비
 - 현재 디시 로그인 구조 기준으로 자동 로그인/세션 복구를 보강했습니다.
* AI 차단 및 스냅샷 안정화
 - AI 차단 시 작성일시, 사유, 스냅샷 저장 일관성을 보강했습니다.
* 시작 안정성 보강
 - Galaxy 계열 기기에서의 시작 안정성과 복구 흐름을 보강했습니다.

v1.1.1 (2026-04-07)
* 봇 설정 export/import 기능 추가
 - JSON 파일로 설정 백업 및 복원이 가능하도록 추가했습니다.
 - 버전 및 스키마 정보를 함께 저장해 호환성 확인이 가능하도록 했습니다.
* 자동 로그인 및 세션 복구 흐름 추가
 - 세션 만료 시 저장된 계정 정보로 자동 복구를 시도합니다.
 - 복구 실패 시 WebView 로그인 화면으로 전환할 수 있습니다.
* 활동 로그 기능 확장
 - 디버그 / 세션·복구 / 오류 필터를 추가했습니다.
 - 활동 로그를 텍스트 파일로 저장하는 기능을 추가했습니다.
 - 좁은 화면에서도 로그 필터 칩을 가로 스크롤로 확인할 수 있도록 했습니다.
* 버그 수정
 - 금지어, 검색어 등 여러 줄 설정 저장 안정화
 - 검색 페이지 처리 및 검색 관련 로그 보강
 - Galaxy 기기에서 exact alarm 제한으로 앱이 시작 직후 종료되던 문제 수정
 - exact alarm 사용이 불가능한 기기에서 fallback 복구 알람으로 계속 동작하도록 개선

v1.1.0 (2026-04-05)
* 로그인 화면 추가
 - 처음 실행 시 별도 화면에서 디시인사이드 계정으로 로그인할 수 있도록 했습니다.
* 스냅샷 기능 전면 개선
 - 게시글 저장 시 실제 화면과 동일한 형태로 댓글을 포함합니다.
 - 보이스리플 재생 가능
 - 디시콘 이미지 표시
 - @멘션 정상 표시
 - 차단된 댓글 별도 파일로 보관
* 스냅샷 뷰어 전면 개선
 - 차단된 댓글 강조 표시
 - 등록순 / 최신순 / 답글순 정렬
 - 원본 파일 열기(핀치 줌 지원) 및 파일 내보내기
* 각 필터 마스터 토글 추가
 - 필터 전체를 한 번에 켜고 끄는 스위치를 추가했습니다.
* DB 대시보드 당겨서 새로고침
* 버그 수정
 - 봇 재시작 시 간헐적 오류 수정
 - 댓글 수 표시 오류 수정 (예: "7/1"을 71로 읽던 문제)
 - 다양한 형식의 갤러리 주소 인식 개선

v1.0.5
* 앱 안의 버전 표시를 실제 버전에 맞게 정리했습니다.

v1.0.4
* 다양한 형식의 갤러리 주소를 더 잘 알아보도록 개선했습니다.
* DB 대시보드에서 스냅샷을 바로 볼 수 있는 뷰어를 추가했습니다.
* 스냅샷 화면에서 최초/최신/차단 시점 파일을 쉽게 전환할 수 있습니다.
* 스냅샷 화면에서 뒤로 가기 후에도 목록 위치가 유지되도록 개선했습니다.
* 차단 증거 스냅샷이 덮어써지지 않도록 보관 방식을 보강했습니다.
* 여러 봇을 함께 사용할 때 스냅샷 요청이 겹치지 않도록 안정화했습니다.
* 스냅샷 저장 중 429 오류가 나지 않도록 처리 흐름을 정리했습니다.
* 화면 구조와 공통 요소를 정리해 전체 사용성이 조금 더 안정적이게 다듬었습니다.

v1.0.1
* 차단 및 검사 로직을 전반적으로 개선했습니다.
* 앱 아이콘과 화면 구성을 조금 다듬었습니다.
* 서비스 프로세스 이름을 정리했습니다.
* 백그라운드 실행 알림 동작을 개선했습니다.
* 기타 사용성을 함께 다듬었습니다.

v1.0
* 각종 오류와 화면 구성을 전반적으로 수정했습니다.
* 다크모드를 추가했습니다.
"""

version_titles = [
    'v1.3.2 (2026-04-22)\n',
    'v1.3.1 (2026-04-21)\n',
    'v1.2.2 (2026-04-15)\n',
    'v1.2.1 (2026-04-10)\n',
    'v1.2.0 (2026-04-09)\n',
    'v1.1.1 (2026-04-07)\n',
    'v1.1.0 (2026-04-05)\n',
    'v1.0.5\n',
    'v1.0.4\n',
    'v1.0.1\n',
    'v1.0\n',
]

requests_payload = {
    'requests': [
        {
            'deleteContentRange': {
                'range': {
                    'startIndex': 1,
                    'endIndex': end_index,
                    'tabId': patch_tab['tabProperties']['tabId']
                }
            }
        },
        {
            'insertText': {
                'location': {
                    'index': 1,
                    'tabId': patch_tab['tabProperties']['tabId']
                },
                'text': text
            }
        },
        {
            'updateParagraphStyle': {
                'range': {
                    'startIndex': 1,
                    'endIndex': len(text) + 1,
                    'tabId': patch_tab['tabProperties']['tabId']
                },
                'paragraphStyle': {'namedStyleType': 'NORMAL_TEXT'},
                'fields': 'namedStyleType'
            }
        }
    ]
}

for title in version_titles:
    start = text.index(title) + 1
    end = start + len(title)
    requests_payload['requests'].append({
        'updateParagraphStyle': {
            'range': {
                'startIndex': start,
                'endIndex': end,
                'tabId': patch_tab['tabProperties']['tabId']
            },
            'paragraphStyle': {'namedStyleType': 'HEADING_1'},
            'fields': 'namedStyleType'
        }
    })

resp = requests.post(BASE_POST, headers=headers, json=requests_payload, timeout=120)
print(resp.status_code)
print(resp.text)
