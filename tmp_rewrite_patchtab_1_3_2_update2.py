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
new_text = (
    'v1.3.2 (2026-04-22)\n'
    '* 마이너 갤러리(mgallery) 게시글 삭제 경로 수정\n'
    ' - 게시글 삭제-only와 도배 방지 샘플/신규 글 삭제가 정상 동작하도록 마이너는 minor_manager, 미니는 mini_manager 경로를 사용하도록 정리했습니다.\n'
    ' - 게시글 삭제 referer도 상세 페이지가 아니라 목록 URL 기준으로 보정했습니다.\n'
    '* 게시글 삭제 토큰 처리 보강\n'
    ' - 게시글 삭제 요청의 ci_t는 브라우저 실제 요청 기준에 맞춰 cookie의 ci_c를 사용하도록 정리했습니다.\n'
    '* 도배 감지 샘플 계산 보정\n'
    ' - 최신 글 전체가 아니라 유동/깡계 후보만 추린 뒤 그중 최신 N개를 샘플로 사용하도록 수정했습니다.\n'
    '* 삭제 진단 로그 추가\n'
    ' - 디버그 모드에서 gallType, gallId, 글번호, 실제 deleteUrl과 referer를 확인할 수 있도록 했습니다.\n\n'
    'v1.3.1 (2026-04-21)\n'
    '* 도배 방지 기능 추가\n'
    ' - 일정 빈도 이상으로 유동/깡계 글이 올라오면 도배 방지 모드를 작동하여 새로 올라오는 유동/깡계 글을 자동 삭제합니다.\n'
    '* 개념글/말머리 주소 인식 지원\n'
    ' - 이제 개념글 목록, 특정 말머리 목록 주소를 구분하여 감지합니다. 개념글 페이지나 특정 말머리 페이지를 별도로 관리할 수 있습니다.\n'
    '* 기타\n'
    ' - 꺼진 필터는 검사에 사용하지 않도록 패치하여 렉을 줄였습니다.\n'
    ' - 디버그 로그에 성능 로그를 추가해 상세 fetch, 댓글 fetch, 스냅샷 저장, 게시글 전체 처리 시간을 확인할 수 있도록 했습니다.\n\n'
)
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
                'text': new_text
            }
        },
        {
            'updateParagraphStyle': {
                'range': {
                    'startIndex': 1,
                    'endIndex': 18,
                    'tabId': patch_tab['tabProperties']['tabId']
                },
                'paragraphStyle': {'namedStyleType': 'HEADING_1'},
                'fields': 'namedStyleType'
            }
        },
        {
            'updateParagraphStyle': {
                'range': {
                    'startIndex': 18,
                    'endIndex': 415,
                    'tabId': patch_tab['tabProperties']['tabId']
                },
                'paragraphStyle': {'namedStyleType': 'NORMAL_TEXT'},
                'fields': 'namedStyleType'
            }
        },
        {
            'updateParagraphStyle': {
                'range': {
                    'startIndex': 415,
                    'endIndex': 432,
                    'tabId': patch_tab['tabProperties']['tabId']
                },
                'paragraphStyle': {'namedStyleType': 'HEADING_1'},
                'fields': 'namedStyleType'
            }
        },
        {
            'updateParagraphStyle': {
                'range': {
                    'startIndex': 432,
                    'endIndex': len(new_text) + 1,
                    'tabId': patch_tab['tabProperties']['tabId']
                },
                'paragraphStyle': {'namedStyleType': 'NORMAL_TEXT'},
                'fields': 'namedStyleType'
            }
        }
    ]
}
resp = requests.post(BASE_POST, headers=headers, json=requests_payload, timeout=120)
print(resp.status_code)
print(resp.text)
