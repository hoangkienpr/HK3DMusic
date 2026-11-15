p = 'app/src/main/kotlin/com/hk/app/LibraryRepository.kt'
lines = open(p, encoding='utf-8').read().splitlines()
# playlist section is from line 751 (blank) to 826 (blank before /**)
start = 750  # 0-based: line 751
end = 826    # exclusive
del lines[start:end]
open(p, 'w', encoding='utf-8').write('\n'.join(lines)+'\n')
print('done; new count', len(lines))
