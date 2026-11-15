p = 'app/src/main/kotlin/com/hk/app/App.kt'
lines = open(p, encoding='utf-8').read().splitlines()
# find function line
fn_idx = next(i for i,l in enumerate(lines) if 'fun MusicPlayerApp' in l)
imports = []
body = []
for i,l in enumerate(lines):
    if l.startswith('import ') and i+1 > fn_idx:
        imports.append(l)
    else:
        body.append(l)
# dedupe imports
seen=set(); uniq=[]
for imp in imports:
    if imp not in seen:
        seen.add(imp); uniq.append(imp)
# find top import block end in body (first non-import, non-blank after package)
# collect top imports already present
top_imports=[l for l in body if l.startswith('import ')]
# remove misplaced imports from body
body2=[l for l in body if not l.startswith('import ')]
# insert uniq imports into top: after package line
pkg_idx = next(i for i,l in enumerate(body2) if l.startswith('package '))
# find end of existing top imports
end_imp = pkg_idx+1
while end_imp < len(body2) and (body2[end_imp].startswith('import ') or body2[end_imp].strip()==''):
    end_imp += 1
new = body2[:end_imp] + uniq + body2[end_imp:]
open(p,'w',encoding='utf-8').write('\n'.join(new)+'\n')
print('moved', len(uniq), 'imports')
