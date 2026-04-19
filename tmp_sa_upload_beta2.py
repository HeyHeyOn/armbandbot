from google.oauth2 import service_account
from google.auth.transport.requests import Request
import requests
import sys

SCOPES=['https://www.googleapis.com/auth/drive']
creds=service_account.Credentials.from_service_account_file(r'C:\Users\Administrator\.config\gws\service-account.json', scopes=SCOPES)
creds.refresh(Request())
folder_id='1D9_B1pr8qycWJkxuPIVgl1I6nib6ivi9'
file_name='완장봇_v1.3.0_beta2.apk'
file_path=r'app\\build\\outputs\\apk\\debug\\완장봇_v1.3.0_beta2.apk'

metadata = {
    'name': file_name,
    'parents': [folder_id],
}

with open(file_path,'rb') as f:
    r=requests.post(
        'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart',
        headers={'Authorization': f'Bearer {creds.token}'},
        files={
            'metadata': ('metadata', requests.compat.json.dumps(metadata), 'application/json; charset=UTF-8'),
            'file': (file_name, f, 'application/vnd.android.package-archive'),
        },
        timeout=300,
    )
print(r.status_code)
print(r.text[:2000])
