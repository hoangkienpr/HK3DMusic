@echo off
setlocal EnableDelayedExpansion
rem ============================================================================
rem  release-github.bat
rem  Dong goi APK release, commit/push source len GitHub, va tao GitHub Release
rem  voi APK dinh kem.
rem
rem  Cach dung:
rem    release-github.bat                 # tu dong bump version, build, push, release
rem    release-github.bat -NoBump         # giu nguyen version hien tai
rem    release-github.bat -Notes "..."    # tu dat release notes
rem    release-github.bat -NotesFile x.md # lay release notes tu file
rem    release-github.bat -NoPush         # build APK va tao release nhung KHONG push git
rem    release-github.bat -DryRun         # chi build APK, KHONG bump / git / release (test)
rem ============================================================================
set "PSFILE=%TEMP%\hk3dmusic-release-%RANDOM%.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[IO.File]::ReadAllText('%~f0'); $i=$s.LastIndexOf('###-POWERSHELL-###'); [IO.File]::WriteAllText('%PSFILE%', $s.Substring($i), (New-Object System.Text.UTF8Encoding $false))"
if errorlevel 1 ( echo Khong tach duoc phan PowerShell & pause & exit /b 1 )
set "REL_ROOT=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%PSFILE%" %*
set "RC=%ERRORLEVEL%"
del "%PSFILE%" >nul 2>&1
if not "%RC%"=="0" ( echo. & echo THAT BAI - xem loi o tren & pause & exit /b %RC% )
echo.
echo XONG.
pause
exit /b 0

###-POWERSHELL-###
param(
  [string]$Root,
  [switch]$NoBump,
  [switch]$NoPush,
  [switch]$DryRun,
  [string]$Notes,
  [string]$NotesFile
)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = $env:REL_ROOT }
if (-not $Root) { $Root = (Get-Location).Path }
$Root = $Root.TrimEnd('\').TrimEnd('"')
$gradleFile = Join-Path $Root 'app\build.gradle.kts'
$apkOut     = Join-Path $Root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $gradleFile)) { throw "Khong tim thay $gradleFile" }

function Read-Gradle($path) {
  $text = Get-Content -Raw -Encoding UTF8 $path
  $code = [regex]::Match($text, 'versionCode\s*=\s*(\d+)').Groups[1].Value
  $name = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
  if (-not $code -or -not $name) { throw "Khong doc duoc versionCode/versionName trong $path" }
  return @{ Code = [int]$code; Name = $name; Text = $text }
}

$info    = Read-Gradle $gradleFile
$newCode = $info.Code
$newName = $info.Name

if (-not $NoBump -and -not $DryRun) {
  $parts = $info.Name.Split('.')
  $parts[$parts.Length - 1] = ([int]$parts[$parts.Length - 1] + 1).ToString()
  $newName = $parts -join '.'
  $newCode = $info.Code + 1
  $text = $info.Text -replace 'versionCode\s*=\s*\d+', "versionCode = $newCode"
  $text = $text -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$newName`""
  [IO.File]::WriteAllText($gradleFile, $text, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "Version -> $newName (code $newCode)" -ForegroundColor Cyan
} else {
  Write-Host "Giu nguyen version $newName (code $newCode)" -ForegroundColor Cyan
}

Write-Host "Dang build release..." -ForegroundColor Cyan
& (Join-Path $Root 'gradlew.bat') ':app:assembleRelease' '--console=plain'
if ($LASTEXITCODE -ne 0) { throw "Build that bai (exit $LASTEXITCODE)" }
if (-not (Test-Path $apkOut)) { throw "Khong thay APK: $apkOut" }

$target = Join-Path $Root "HK3DMusic-v$newName.apk"
Copy-Item $apkOut $target -Force
Write-Host "APK: $target" -ForegroundColor Green

Push-Location $Root
try {
  if ($DryRun) {
    Write-Host "DRY RUN: bo qua git push va gh release create." -ForegroundColor Yellow
    return
  }
  if (-not $NoPush) {
    Write-Host "Commit va push source..." -ForegroundColor Cyan
    git add app\build.gradle.kts
    git commit -m "v$newName" | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "Khong co gi de commit (hoac commit loi), bo qua." -ForegroundColor Yellow }
    git push origin main
    if ($LASTEXITCODE -ne 0) { throw "git push that bai (exit $LASTEXITCODE)" }
  }

  # Tao release notes: -NotesFile > -Notes > tu sinh tu git log
  if ($NotesFile -and (Test-Path $NotesFile)) {
    $notes = Get-Content -Raw -Encoding UTF8 $NotesFile
  } elseif ($Notes) {
    $notes = $Notes
  } else {
    $log = (git -C $Root log --oneline -12) -join "`n"
    $notes = "HK3DMusic v$newName`n`n$log"
  }

  $tag = "v$newName"
  Write-Host "Tao GitHub release $tag ..." -ForegroundColor Cyan
  gh release create $tag $target --title "HK3DMusic v$newName" --notes $notes
  if ($LASTEXITCODE -ne 0) { throw "gh release create that bai (exit $LASTEXITCODE)" }
  Write-Host "Da day $tag (va APK) len GitHub." -ForegroundColor Green
} finally {
  Pop-Location
}
