# BK-PHENIX: сборка release-APK, подписанного постоянным ключом владельца.
# Запускается владельцем из корня рабочей копии. Пароль ключа вводится в окне и живёт только
# в переменной окружения этого процесса; в файлы и журналы не пишется.
param(
    [string]$Keystore = "$env:USERPROFILE\.bkphenix-keys\bk-phenix-release.p12",
    [string]$OutDir = ".",
    [string]$Pin = "F7F1B3D79DB8E049F41BDA893A0E8767E5F1E1EC0F37EF937060FD04B0B73463"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $Keystore)) { Write-Host "Ключ не найден: $Keystore"; exit 1 }

$secure = Read-Host "Пароль release-ключа (Enter — пропустить release)" -AsSecureString
if ($secure.Length -eq 0) { Write-Host "Release пропущен."; exit 2 }
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $env:BKPHENIX_KS_PASS = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $env:BKPHENIX_KEYSTORE = $Keystore
    & .\gradlew.bat :app:assembleRelease --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "сборка release не удалась" }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    Remove-Item Env:BKPHENIX_KS_PASS -ErrorAction SilentlyContinue
    Remove-Item Env:BKPHENIX_KEYSTORE -ErrorAction SilentlyContinue
}

$apk = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "нет $apk (release не подписан?)" }

# Проверка подписи через apksigner из Android SDK: сертификат обязан совпасть с отпечатком в приложении.
$sdk = (Get-Content local.properties | Where-Object { $_ -match '^sdk\.dir=' }) -replace '^sdk\.dir=', '' -replace '\\\\', '\' -replace '\\:', ':'
$signer = Get-ChildItem "$sdk\build-tools" -Recurse -Filter apksigner.bat -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if ($null -eq $signer) { throw "apksigner не найден в $sdk\build-tools" }
$certs = & $signer.FullName verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "apksigner: подпись недействительна" }
$fp = ($certs | Select-String 'SHA-256 digest:\s*([0-9a-fA-F]+)').Matches[0].Groups[1].Value.ToUpper()
if ($fp -ne $Pin) { throw "сертификат APK $fp не совпадает с отпечатком приложения $Pin" }
Write-Host "Подпись проверена: $fp"
Copy-Item $apk (Join-Path $OutDir "app-release.apk") -Force
exit 0
