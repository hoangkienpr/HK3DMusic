import re
p = 'app/src/main/kotlin/com/hk/app/App.kt'
lines = open(p, encoding='utf-8').read().splitlines()
composable_idx = next(i for i, l in enumerate(lines) if 'fun MusicPlayerApp' in l)
misplaced = []
rest = []
for i, l in enumerate(lines):
    if i < 6:
        rest.append(l)
        continue
    if i + 1 >= composable_idx:
        rest.append(l)
        continue
    if l.startswith('import '):
        misplaced.append(l)
    else:
        rest.append(l)
new = rest[:6] + misplaced + rest[6:]
open(p, 'w', encoding='utf-8').write('\n'.join(new) + '\n')
print('moved', len(misplaced), 'imports')