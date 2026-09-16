$ErrorActionPreference = "Stop"

$jbr = "C:\Program Files\Android\Android Studio\jbr"
if (Test-Path (Join-Path $jbr "bin\java.exe")) {
    $env:JAVA_HOME = $jbr
    $env:Path = "$jbr\bin;$env:Path"
    Write-Host "[WolfTaxi] Java: Android Studio JBR" -ForegroundColor DarkGray
}

$android = Join-Path $PSScriptRoot "android"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (!(Test-Path $sdk)) { throw "Nie znaleziono Android SDK: $sdk" }

$localProperties = Join-Path $android "local.properties"
$apiUrl = $env:WOLFTAXI_API_URL
if ([string]::IsNullOrWhiteSpace($apiUrl) -and (Test-Path $localProperties)) {
    $existing = Get-Content $localProperties | Where-Object { $_ -like 'wolftaxi.apiUrl=*' } | Select-Object -First 1
    if ($existing) { $apiUrl = $existing.Substring('wolftaxi.apiUrl='.Length) }
}

$sdkForGradle = $sdk -replace '\\','/'
$lines = @("sdk.dir=$sdkForGradle")
if (![string]::IsNullOrWhiteSpace($apiUrl)) {
    $lines += "wolftaxi.apiUrl=$apiUrl"
    Write-Host "[WolfTaxi] API: $apiUrl" -ForegroundColor DarkGray
} else {
    Write-Host "[WolfTaxi] UWAGA: brak wolftaxi.apiUrl - aplikacja uruchomi tryb DEMO." -ForegroundColor Yellow
}
Set-Content -Path $localProperties -Value $lines -Encoding ASCII

Set-Location $android
Write-Host "[WolfTaxi] Zatrzymywanie Gradle..." -ForegroundColor Cyan
& .\gradlew.bat --stop

Write-Host "[WolfTaxi] Czyszczenie lokalnych wynikow builda..." -ForegroundColor Cyan
Remove-Item -Recurse -Force ".gradle" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "app\build" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "build" -ErrorAction SilentlyContinue

$adb = Join-Path $sdk "platform-tools\adb.exe"
$devices = & $adb devices | Select-String "\sdevice$"
if (!$devices) {
    Write-Host "[WolfTaxi] Brak uruchomionego emulatora/urzadzenia." -ForegroundColor Yellow
    Write-Host "[WolfTaxi] Uruchom Pixel_7, a potem ponow skrypt." -ForegroundColor Yellow
    exit 2
}

Write-Host "[WolfTaxi] Budowanie i instalacja..." -ForegroundColor Cyan
& .\gradlew.bat clean installDebug --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "[WolfTaxi] Gotowe. Aplikacja zostala zbudowana i zainstalowana." -ForegroundColor Green
