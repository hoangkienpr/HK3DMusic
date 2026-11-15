<#
    Đóng gói APK release cho HK Music Player.

    Cách dùng (mở PowerShell tại thư mục dự án):

        .\release.ps1                 # build + copy APK theo version hiện tại
        .\release.ps1 -Bump           # tăng versionCode +1 và versionName (1.4 -> 1.5) rồi build
        .\release.ps1 -Bump -Install  # build xong cài luôn lên máy qua adb
        .\release.ps1 -Version 2.0    # đặt versionName cụ thể (versionCode vẫn +1)

    Hoặc bấm đúp file release.bat (tự tăng version + cài lên máy).
#>
param(
    [switch]$Bump,
    [switch]$Install,
    [string]$Version
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$gradleFile = Join-Path $root "app\build.gradle.kts"
$apkOut = Join-Path $root "app\build\outputs\apk\release\app-release.apk"

function Read-Gradle($path) {
    $text = Get-Content -Raw -Encoding utf8 $path
    $code = [regex]::Match($text, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    $name = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    if (-not $code -or -not $name) { throw "Không đọc được versionCode/versionName trong $path" }
    return @{ Code = [int]$code; Name = $name; Text = $text }
}

$info = Read-Gradle $gradleFile
$newCode = $info.Code
$newName = $info.Name

if ($Bump -or $Version) {
    if ($Version) {
        $newName = $Version
    } else {
        $parts = $info.Name.Split('.')
        $parts[$parts.Length - 1] = ([int]$parts[$parts.Length - 1] + 1).ToString()
        $newName = $parts -join '.'
    }
    $newCode = $info.Code + 1

    $text = $info.Text -replace 'versionCode\s*=\s*\d+', "versionCode = $newCode"
    $text = $text -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$newName`""
    # Ghi bằng UTF8 không BOM để Gradle đọc bình thường.
    [System.IO.File]::WriteAllText($gradleFile, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Version -> $newName (code $newCode)" -ForegroundColor Cyan
} else {
    Write-Host "Giữ version hiện tại: $newName (code $newCode)" -ForegroundColor Cyan
}

Write-Host "Đang build release..." -ForegroundColor Cyan
& (Join-Path $root "gradlew.bat") ":app:assembleRelease" "--console=plain"
if ($LASTEXITCODE -ne 0) { throw "Build thất bại (exit $LASTEXITCODE)" }
if (-not (Test-Path $apkOut)) { throw "Không thấy APK: $apkOut" }

$target = Join-Path $root "HK3DMusic-v$newName.apk"
Copy-Item $apkOut $target -Force
Write-Host "APK: $target" -ForegroundColor Green

# Kiểm tra chữ ký nếu có apksigner.
$apksigner = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\36.0.0\apksigner.bat"
if (Test-Path $apksigner) {
    & $apksigner verify --print-certs $target | Select-Object -First 2 | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "Không tìm thấy apksigner, bỏ qua bước kiểm tra chữ ký." -ForegroundColor Yellow
}

if ($Install) {
    Write-Host "Đang cài lên máy..." -ForegroundColor Cyan
    $devices = & adb devices | Select-String "device$"
    if (-not $devices) { throw "adb không thấy máy nào. Kiểm tra cáp / Wireless debugging." }
    & adb install -r $target
    if ($LASTEXITCODE -ne 0) { throw "Cài đặt thất bại (exit $LASTEXITCODE)" }
    & adb shell am start -n com.hk.app/.MainActivity | Out-Null
    Write-Host "Đã cài và mở app." -ForegroundColor Green
}

Write-Host "Xong." -ForegroundColor Green
