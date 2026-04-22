import json
from pathlib import Path
import requests

client = json.loads(Path(r'C:\Users\Administrator\.config\gws\client_secret.json').read_text(encoding='utf-8'))['installed']
token = json.loads(Path(r'C:\Users\Administrator\.config\gws\token_cache.json').read_text(encoding='utf-8'))
resp = requests.post(
    client['token_uri'],
    data={
        'client_id': client['client_id'],
        'client_secret': client['client_secret'],
        'refresh_token': token['refresh_token'],
        'grant_type': 'refresh_token',
    },
    timeout=60,
)
print(resp.status_code)
print(resp.text)
if resp.ok:
    data = resp.json()
    token['access_token'] = data['access_token']
    token['expires_in'] = data.get('expires_in', token.get('expires_in'))
    token['token_type'] = data.get('token_type', token.get('token_type', 'Bearer'))
    Path(r'C:\Users\Administrator\.config\gws\token_cache.json').write_text(json.dumps(token, ensure_ascii=False, indent=2), encoding='utf-8')
