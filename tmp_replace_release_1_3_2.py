import json
import requests
from pathlib import Path

TOKEN = json.loads(Path(r'C:\Users\Administrator\.config\gws\token_cache.json').read_text(encoding='utf-8'))['access_token']
FILE_ID = '1NgWtWqghsrUWEYsXGnv1KGmDkt7RwbQ7'
FILE_PATH = Path(r'C:\Users\Administrator\.openclaw\workspace\projects\armbandbot\완장봇_v1.3.2.apk')
url = f'https://www.googleapis.com/upload/drive/v3/files/{FILE_ID}?uploadType=media'
with FILE_PATH.open('rb') as f:
    r = requests.patch(
        url,
        headers={
            'Authorization': f'Bearer {TOKEN}',
            'Content-Type': 'application/vnd.android.package-archive'
        },
        data=f,
        timeout=600
    )
    print(r.status_code)
    print(r.text)
