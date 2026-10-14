# Publish a new RevTale version as a GitHub release; the app on the phone then offers it as an update.
# Usage:  .\scripts\release.ps1 1.0.1 "What changed, one line per item"
# Builds on this computer so the APK carries the same signing key as the installed app (Android refuses
# updates signed with a different key).
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Notes
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

$gradle = "app\build.gradle.kts"
$text = Get-Content $gradle -Raw
$code = [int]([regex]::Match($text, 'versionCode = (\d+)').Groups[1].Value) + 1
$text = $text -replace 'versionCode = \d+', "versionCode = $code"
$text = $text -replace 'versionName = "[^"]*"', "versionName = ""$Version"""
# no byte-order mark: Gradle's Kotlin script compiler rejects it
[System.IO.File]::WriteAllText((Resolve-Path $gradle), $text, (New-Object System.Text.UTF8Encoding $false))

# the release key lives outside the repo; local.properties names it (releaseStoreFile / releaseStorePassword)
if (-not (Select-String -Path "local.properties" -Pattern "^releaseStoreFile=" -Quiet)) { throw "local.properties has no release key" }
.\gradlew.bat testDebugUnitTest assembleRelease -q
if ($LASTEXITCODE -ne 0) { throw "Build failed" }
$apk = "build\revtale-$Version.apk"
Copy-Item "app\build\outputs\apk\release\app-release.apk" $apk -Force

# git and gh print progress on stderr, which Windows PowerShell would turn into errors under "Stop"
$ErrorActionPreference = "Continue"
git add $gradle 2>$null
git commit -q -m "Release $Version" 2>$null
git push -q 2>$null
$branch = git branch --show-current
gh release create "v$Version" $apk --target $branch --title "RevTale $Version" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "Publishing the release failed" }
Write-Host "Published v$Version (versionCode $code)."
