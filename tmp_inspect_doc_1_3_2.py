import json
from pathlib import Path
p = Path(r'C:\Users\Administrator\.openclaw\workspace\projects\armbandbot\tmp_doc_full_1_3_2.json')
doc = json.loads(p.read_text(encoding='utf-8'))
out = []
out.append(str(doc.keys()))
out.append(f"tabs {len(doc.get('tabs', []))}")
for i, tab in enumerate(doc.get('tabs', [])):
    props = tab.get('tabProperties', {})
    out.append(f"TAB {i} {props.get('tabId')} {props.get('title')}")
    body = tab.get('documentTab', {}).get('body', {}).get('content', [])
    text = []
    for el in body[:80]:
        para = el.get('paragraph')
        if not para:
            continue
        for e in para.get('elements', []):
            tr = e.get('textRun')
            if tr:
                text.append(tr.get('content', ''))
    out.append(''.join(text)[:4000].replace('\n', '\\n'))
    out.append('---')
Path(r'C:\Users\Administrator\.openclaw\workspace\projects\armbandbot\tmp_doc_inspect_1_3_2.txt').write_text('\n'.join(out), encoding='utf-8')
print('saved')
