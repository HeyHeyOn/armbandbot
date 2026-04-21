from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload
import json

service_account_file = r"C:\Users\Administrator\.config\gws\service-account.json"
folder_id = "1D9_B1pr8qycWJkxuPIVgl1I6nib6ivi9"
local_path = r"C:\Users\Administrator\.openclaw\workspace\projects\armbandbot\app\build\outputs\apk\debug\완장봇_v1.3.1_beta1.apk"
new_name = "완장봇_v1.3.1_beta1.apk"

creds = service_account.Credentials.from_service_account_file(
    service_account_file,
    scopes=["https://www.googleapis.com/auth/drive"]
)
service = build("drive", "v3", credentials=creds)

query = f"name = '{new_name}' and '{folder_id}' in parents and trashed = false"
resp = service.files().list(q=query, spaces="drive", fields="files(id,name,size,modifiedTime)").execute()
files = resp.get("files", [])
media = MediaFileUpload(local_path, mimetype="application/vnd.android.package-archive", resumable=False)

if files:
    file_id = files[0]["id"]
    result = service.files().update(
        fileId=file_id,
        media_body=media,
        body={"name": new_name},
        fields="id,name,size,modifiedTime,webViewLink"
    ).execute()
    result["action"] = "updated"
else:
    result = service.files().create(
        body={"name": new_name, "parents": [folder_id]},
        media_body=media,
        fields="id,name,size,modifiedTime,webViewLink"
    ).execute()
    result["action"] = "created"

print(json.dumps(result, ensure_ascii=False))
