import json
from pathlib import Path
import requests

TOKEN = json.loads(Path(r'C:\Users\Administrator\.config\gws\token_cache.json').read_text(encoding='utf-8'))['access_token']
DOC_ID = '1AHVTh0MBFlqyCkFmPma0uYkU5k4g-hMTcOMmh6LGeGU'
url = f'https://docs.googleapis.com/v1/documents/{DOC_ID}?includeTabsContent=true'
r = requests.get(url, headers={'Authorization': f'Bearer {TOKEN}'}, timeout=120)
print(r.status_code)
Path(r'C:\Users\Administrator\.openclaw\workspace\projects\armbandbot\tmp_doc_full_1_3_2.json').write_text(r.text, encoding='utf-8')
print('saved')
