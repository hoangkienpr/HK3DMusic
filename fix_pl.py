p = 'app/src/main/kotlin/com/hk/app/LibraryRepository.kt'
lines = open(p, encoding='utf-8').read().splitlines()
start = next(i for i,l in enumerate(lines) if l.strip().startswith('// ---------------------------------------------------------------- Playlists'))
# find end: next line after removeFromPlaylist block that is blank then /** or fun
end = start
for i in range(start+1, len(lines)):
    if lines[i].strip().startswith('/**') or lines[i].strip().startswith('fun ') or lines[i].strip().startswith('private fun') or lines[i].strip().startswith('suspend fun'):
        end = i
        break
# remove from start-1 (blank line before comment) to end-1
del lines[start-1:end]
open(p, 'w', encoding='utf-8').write('\n'.join(lines)+'\n')
print('removed lines', start, 'to', end)
