# Lance le build release IPTV2 avec vérifications préalables :
#   - JAVA_HOME / java disponibles (auto-détection du JDK Temurin sinon)
#   - keystore app\iptv2-release.jks présent
#   - local.properties rempli (sdk.dir + mots de passe keystore)
#   - git pull (sauf -NoPull), puis gradlew.bat assembleRelease
#
# Usage :
#   powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1 -NoPull

param([switch]$NoPull)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

Write-Host "=== IPTV2 : build release ===" -ForegroundColor Cyan

# ------------------------------------------------------------ JAVA_HOME
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
           Where-Object Name -like "jdk-17*" | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) {
        Write-Host "ERREUR : JDK 17 introuvable. Lance d'abord scripts\setup-build-windows.ps1" -ForegroundColor Red
        exit 1
    }
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$($jdk.FullName)\bin;$env:Path"
}
Write-Host "JAVA_HOME : $env:JAVA_HOME"

# ------------------------------------------------------------ Prérequis
if (-not (Test-Path "app\iptv2-release.jks")) {
    Write-Host "ERREUR : app\iptv2-release.jks manquant — copie le keystore dans app\." -ForegroundColor Red
    exit 1
}
if (-not (Test-Path "local.properties")) {
    Write-Host "ERREUR : local.properties manquant — lance scripts\setup-build-windows.ps1" -ForegroundColor Red
    exit 1
}
$props = Get-Content "local.properties" -Raw
if ($props -match "A_REMPLIR") {
    Write-Host "ERREUR : mots de passe du keystore non renseignés dans local.properties." -ForegroundColor Red
    exit 1
}
if ($props -notmatch "sdk\.dir") {
    Write-Host "ERREUR : sdk.dir absent de local.properties — lance scripts\setup-build-windows.ps1" -ForegroundColor Red
    exit 1
}

# Retire un éventuel BOM UTF-8 en tête de local.properties : Java/Gradle ne le
# strippe pas et la clé sdk.dir ne serait alors pas reconnue (« SDK location
# not found » malgré un chemin correct).
$octets = [IO.File]::ReadAllBytes("$RepoRoot\local.properties")
if ($octets.Length -ge 3 -and $octets[0] -eq 0xEF -and $octets[1] -eq 0xBB -and $octets[2] -eq 0xBF) {
    [IO.File]::WriteAllBytes("$RepoRoot\local.properties", $octets[3..($octets.Length - 1)])
    Write-Host "BOM retiré de local.properties" -ForegroundColor Yellow
}

# Vérifie que sdk.dir pointe vers un dossier existant ; répare automatiquement
# avec le SDK standard (%LOCALAPPDATA%\Android\Sdk) si le chemin est invalide
# (ancien bug d'échappement des antislashs).
$sdkLine = (Get-Content "local.properties" | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1)
$sdkPath = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\\\', '\' -replace '/', '\'
if (-not (Test-Path (Join-Path $sdkPath "platforms"))) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path (Join-Path $defaultSdk "platforms")) {
        $fixed = $defaultSdk -replace '\\', '/'
        $lignes = (Get-Content "local.properties") |
            ForEach-Object { if ($_ -match '^sdk\.dir=') { "sdk.dir=$fixed" } else { $_ } }
        [IO.File]::WriteAllLines("$RepoRoot\local.properties", [string[]]$lignes)
        $sdkPath = $defaultSdk
        Write-Host "sdk.dir invalide → corrigé vers $fixed" -ForegroundColor Yellow
    } else {
        Write-Host "ERREUR : SDK Android introuvable ($sdkPath). Lance scripts\setup-build-windows.ps1" -ForegroundColor Red
        exit 1
    }
}
# Ceinture et bretelles : Gradle accepte aussi la variable d'environnement.
$env:ANDROID_HOME = $sdkPath

# ------------------------------------------------------- Mise à jour git
if (-not $NoPull) {
    Write-Host "git pull..." -ForegroundColor Yellow
    git pull
}

# ------------------------------------------------------------------ Build
Write-Host "Build en cours (assembleRelease)..." -ForegroundColor Yellow
& .\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "=== ECHEC DU BUILD ===" -ForegroundColor Red
    Write-Host "Copie le message d'erreur ci-dessus et envoie-le pour correction."
    exit 1
}

# ------------------------------------------------------------------ Bilan
$apk = "app\build\outputs\apk\release\app-release.apk"
Write-Host ""
Write-Host "=== BUILD REUSSI ===" -ForegroundColor Green
if (Test-Path $apk) {
    $size = [math]::Round((Get-Item $apk).Length / 1MB, 1)
    Write-Host "APK : $apk ($size Mo)"
}
$updateDir = if ($env:IPTV2_UPDATE_DIR) { $env:IPTV2_UPDATE_DIR } else { "Z:\update-iptv2" }
if (Test-Path $updateDir) {
    Write-Host "Publié dans : $updateDir (APK + version.json)"
} else {
    Write-Host "NB : dossier update '$updateDir' inaccessible — la copie auto a pu échouer." -ForegroundColor Yellow
    Write-Host "    L'APK reste disponible dans $apk"
}
Write-Host ""
Write-Host "Rappel déploiement : copier l'APK sur iptv2.nicotv.ovh AVANT version.json."
