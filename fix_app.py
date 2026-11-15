p = 'app/src/main/kotlin/com/hk/app/App.kt'
lines = open(p, encoding='utf-8').read().splitlines()
imports = []
body = []
for l in lines:
    if l.startswith('import '):
        imports.append(l)
    else:
        body.append(l)
# dedupe imports preserving order
seen = set()
uniq = []
for imp in imports:
    if imp not in seen:
        seen.add(imp)
        uniq.append(imp)
# remove leading blanks at top of body
while body and body[0].strip() == '':
    body.pop(0)
# find package line
pkg_idx = next(i for i,l in enumerate(body) if l.startswith('package '))
# build: package, blank, imports, blank, rest
out = [body[pkg_idx]] + [''] + uniq + [''] + [l for l in body if l != body[pkg_idx]]
open(p, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('imports:', len(uniq), 'body lines:', len(out))