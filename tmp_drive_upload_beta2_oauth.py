import json
import requests

TOKEN = 'ya29.a0Aa7MYipmvGKlmOfrgW33KQwL9X5QAtt3SDJ6RucjXMlX6mWvKsi-ry20Ldc8jbK5giMX8WCI8CAgFqBWme5oPvAvnktyQLLeLizIasMOJBNFTnasWb35xX4jIcL7XY43Dv-mAwlz6jMq6Q9l1eZzA19OSzxnmRapfXg9qwLWKJnbr2GFlV3D8BYU_DahtbKZnK34VQIaCgYKAR4SAQ8SFQHGX2MiZi2M1NtEkrwxCzXojq3Qvw0206'
url = 'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart'
metadata = {
    'name': '완장봇_v1.3.1_beta2.apk',
    'parents': ['1D9_B1pr8qycWJkxuPIVgl1I6nib6ivi9']
}
with open('완장봇_v1.3.1_beta2.apk', 'rb') as f:
    files = {
        'metadata': ('metadata', json.dumps(metadata), 'application/json; charset=UTF-8'),
        'file': ('완장봇_v1.3.1_beta2.apk', f, 'application/vnd.android.package-archive')
    }
    r = requests.post(url, headers={'Authorization': f'Bearer {TOKEN}'}, files=files, timeout=300)
    print(r.status_code)
    print(r.text)
