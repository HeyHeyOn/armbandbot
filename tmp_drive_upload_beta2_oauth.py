import json
import requests

TOKEN = 'ya29.a0Aa7MYip7_di-dGTN5cI439xfO204PWGV0l2zhe4wJD8SaPm39ggSZgjBza_27eO3ilJhuOqv_uVDJmORX8Wh6TuWKWrO5JN3ljgqqsFzlNQuuQeNF3n70shFzunjNhdFzVKn-hame-XMQHxhzCwEVgGxkjg9K8DgnMzQD8uCXlE4C0Fj4TG6ZPbE6bveXhWQ3gYLr4waCgYKAYUSAQ8SFQHGX2Miaq6drr2u-ddkNE16IG9-Cw0206'
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
