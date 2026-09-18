param(
    [Parameter(Mandatory=$true)]
    [string]$Url
)
$ErrorActionPreference = "Stop"
$android = Join-Path $PSScriptRoot "android"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (!(Test-Path $sdk)) { throw "Nie znaleziono Android SDK: $sdk" }
$url = $Url.Trim().TrimEnd('/')
if ($url -notmatch '^https?://') { throw "URL musi zaczynac sie od http:// albo https://" }
$sdkForGradle = $sdk -replace '\\','/'
Set-Content -Path (Join-Path $android 'local.properties') -Value @(
    "sdk.dir=$sdkForGradle",
    "wolftaxi.apiUrl=$url"
) -Encoding ASCII
Write-Host "[WolfTaxi] API ustawione na: $url" -ForegroundColor Green
