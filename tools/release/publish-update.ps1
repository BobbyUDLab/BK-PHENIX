# BK-PHENIX: публикация обновления на сервер владельца (Nextcloud): APK и manifest.json в BK-PHENIX/updates.
# Пишет учётная запись владельца (у пользователя телефонов bkphenix папка updates только для чтения).
# Логин и пароль приложения владельца запрашиваются один раз и хранятся зашифрованными DPAPI
# текущего пользователя Windows: %USERPROFILE%\.bkphenix-keys\nc-publisher.xml.
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [string]$Notes = "",
    [string]$Server = "https://kutyrins.ru/nc",
    [string]$Folder = "BK-PHENIX/updates",
    [string]$Pin = "F7F1B3D79DB8E049F41BDA893A0E8767E5F1E1EC0F37EF937060FD04B0B73463"
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if (-not (Test-Path $Apk)) { throw "нет файла $Apk" }

$credFile = "$env:USERPROFILE\.bkphenix-keys\nc-publisher.xml"
if (Test-Path $credFile) {
    $cred = Import-Clixml $credFile
} else {
    Write-Host "Нужен пароль приложения Nextcloud владельца (Настройки → Безопасность → «Создать пароль приложения», имя: BK-PHENIX publish)."
    $cred = Get-Credential -Message "Логин владельца Nextcloud и пароль приложения"
    New-Item -ItemType Directory -Force (Split-Path $credFile) | Out-Null
    $cred | Export-Clixml $credFile
}
$user = $cred.UserName
$pass = $cred.GetNetworkCredential().Password
$auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${user}:${pass}"))
$headers = @{ Authorization = $auth }

$name = "BK-PHENIX-$VersionName.apk"
$size = (Get-Item $Apk).Length
$sha = (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLower()
$base = "$Server/remote.php/dav/files/$([Uri]::EscapeDataString($user))/$Folder"

Write-Host "Загрузка $name ($size байт, SHA-256 $sha)…"
Invoke-WebRequest -Uri "$base/$name" -Method Put -InFile $Apk -Headers $headers -ContentType "application/octet-stream" -UseBasicParsing | Out-Null

$manifest = [ordered]@{
    format = 1; versionCode = $VersionCode; versionName = $VersionName; apk = $name
    size = $size; sha256 = $sha; certSha256 = $Pin; notes = $Notes
} | ConvertTo-Json -Compress
$tmp = New-TemporaryFile
[IO.File]::WriteAllText($tmp, $manifest, (New-Object Text.UTF8Encoding $false))
Invoke-WebRequest -Uri "$base/manifest.json" -Method Put -InFile $tmp -Headers $headers -ContentType "application/json" -UseBasicParsing | Out-Null
Remove-Item $tmp
Write-Host "Опубликовано: $VersionName ($VersionCode)."
