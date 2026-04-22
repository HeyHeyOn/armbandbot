import json
from pathlib import Path
import requests

TOKEN = json.loads(Path(r'C:\Users\Administrator\.config\gws\token_cache.json').read_text(encoding='utf-8'))['access_token']
DOC_ID = '1AHVTh0MBFlqyCkFmPma0uYkU5k4g-hMTcOMmh6LGeGU'
TAB_ID = 't.qvnp48gaes2n'
BASE = f'https://docs.googleapis.com/v1/documents/{DOC_ID}:batchUpdate'

requests_payload = {
    'requests': [
        {
            'insertText': {
                'location': {
                    'segmentId': TAB_ID,
                    'index': 1
                },
                'text': 'v1.3.2 (2026-04-22)\n- 마이너 갤러리(mgallery) 게시글 삭제 경로를 보정해 게시글 삭제-only와 도배 방지 샘플/신규 글 삭제가 정상 동작하도록 수정\n- 도배 방지 삭제 진단용 최소 디버그 로그 추가 (gallType, gallId, 글번호, 실제 deleteUrl 확인 가능)\n\n'
            }
        },
        {
            'updateParagraphStyle': {
                'range': {
                    'segmentId': TAB_ID,
                    'startIndex': 1,
                    'endIndex': 18
                },
                'paragraphStyle': {
                    'namedStyleType': 'HEADING_1'
                },
                'fields': 'namedStyleType'
            }
        },
        {
            'updateParagraphStyle': {
                'range': {
                    'segmentId': TAB_ID,
                    'startIndex': 18,
                    'endIndex': 151
                },
                'paragraphStyle': {
                    'namedStyleType': 'NORMAL_TEXT'
                },
                'fields': 'namedStyleType'
            }
        }
    ]
}

resp = requests.post(
    BASE,
    headers={
        'Authorization': f'Bearer {TOKEN}',
        'Content-Type': 'application/json'
    },
    json=requests_payload,
    timeout=120
)
print(resp.status_code)
print(resp.text)
