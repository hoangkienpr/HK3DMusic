p = 'app/src/main/kotlin/com/hk/app/App.kt'
lines = open(p, encoding='utf-8').read().splitlines()
start = next(i for i,l in enumerate(lines) if l.strip().startswith('// ------------------------------------------------------- Playlists'))
end = next(i for i in range(start+1, len(lines)) if lines[i].strip().startswith('// ------------------------------------------------------- Download quality picker'))
# remove from start-1 (blank line) to end-1
del lines[start-1:end]
open(p, 'w', encoding='utf-8').write('\n'.join(lines)+'\n')
print('removed', start, 'to', end)
