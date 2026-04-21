import json, requests
cfg=json.load(open(r'C:\Users\Administrator\.config\gws\client_secret.json','r',encoding='utf-8'))['installed']
code='4/0Aci98E817ynieQnSrN_8jOSEyu_Lg2y7Vm8-wqnAHPO_wy-mOIclbjLN1znFy2Q2BxPWBQ'
r=requests.post(cfg['token_uri'], data={
    'code': code,
    'client_id': cfg['client_id'],
    'client_secret': cfg['client_secret'],
    'redirect_uri': cfg['redirect_uris'][0],
    'grant_type': 'authorization_code'
}, timeout=30)
print(r.status_code)
print(r.text)
if r.ok:
    open(r'C:\Users\Administrator\.config\gws\token_cache.json','w',encoding='utf-8').write(r.text)
