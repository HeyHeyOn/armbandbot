import json
import requests
from pathlib import Path

TOKEN = json.loads(Path(r'C:\Users\Administrator\.config\gws\token_cache.json').read_text(encoding='utf-8'))['access_token']
FOLDER_ID = '1U-TFduxP6D0jTvNuBvgkkjNaTXvP_Kgc'
FILE_PATH = Path(r'C:\Users\Administrator\.openclaw\workspace\projects\armbandbot\완장봇_v1.3.2.apk')
metadata = {
    'name': '완장봇_v1.3.2.apk',
    'parents': [FOLDER_ID]
}
files = {
    'metadata': ('metadata', json.dumps(metadata), 'application/json; charset=UTF-8'),
    'file': ('완장봇_v1.3.2.apk', FILE_PATH.open('rb'), 'application/vnd.android.package-archive')
}
r = requests.post(
    'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart',
    headers={'Authorization': f'Bearer {TOKEN}'},
    files=files,
    timeout=600
)
print(r.status_code)
print(r.text)
