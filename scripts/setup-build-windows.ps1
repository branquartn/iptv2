# Prépare l'environnement de build IPTV2 sur un PC Windows :
#   - JDK 17 (Temurin) via winget s'il manque
#   - Android SDK (cmdline-tools + platform 34 + build-tools 34.0.0)
#   - local.properties pré-rempli (sdk.dir + placeholders keystore)
#
# Usage : clic droit → « Exécuter avec PowerShell », ou :
#   powershell -ExecutionPolicy Bypass -File scripts\setup-build-windows.ps1
#
# Relançable sans risque : chaque étape est sautée si déjà en place.

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$SdkRoot  = Join-Path $env:LOCALAPPDATA "Android\Sdk"

Write-Host "=== IPTV2 : préparation de l'environnement de build ===" -ForegroundColor Cyan

# ---------------------------------------------------------------- JDK 17
$javaOk = $false
try {
    $v = (& java -version 2>&1 | Out-String)
    if ($v -match '"(1[7-9]|2[0-9])') { $javaOk = $true; Write-Host "JDK déjà présent : $($v.Split("`n")[0])" }
} catch {}
if (-not $javaOk) {
    Write-Host "Installation du JDK 17 (Temurin) via winget..." -ForegroundColor Yellow
    winget install --id EclipseAdoptium.Temurin.17.JDK --accept-source-agreements --accept-package-agreements
}

# Définit JAVA_HOME (utilisateur) si absent ou invalide, pour que gradlew.bat
# trouve le JDK même sans rouvrir la session.
$javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User")
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
           Where-Object Name -like "jdk-17*" | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) {
        [Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk.FullName, "User")
        $env:JAVA_HOME = $jdk.FullName
        $env:Path = "$($jdk.FullName)\bin;$env:Path"
        Write-Host "JAVA_HOME défini sur $($jdk.FullName)" -ForegroundColor Green
    } else {
        Write-Host "ATTENTION : JDK 17 introuvable dans C:\Program Files\Eclipse Adoptium." -ForegroundColor Red
        Write-Host "Rouvre un terminal et relance ce script après l'installation du JDK."
    }
} else {
    $env:JAVA_HOME = $javaHome
}

# ---------------------------------------------------- Android cmdline-tools
$CmdlineTools = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $CmdlineTools)) {
    Write-Host "Téléchargement des Android command-line tools..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $SdkRoot | Out-Null
    $zip = Join-Path $env:TEMP "cmdline-tools.zip"
    # Version stable des cmdline-tools (11076708 = v12). Mettre à jour au besoin :
    # https://developer.android.com/studio#command-line-tools-only
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $zip
    $tmp = Join-Path $env:TEMP "cmdline-tools-extract"
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    # Le zip contient un dossier 'cmdline-tools' qui doit devenir 'cmdline-tools\latest'
    $dest = Join-Path $SdkRoot "cmdline-tools\latest"
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Move-Item -Path (Join-Path $tmp "cmdline-tools") -Destination $dest -Force
    Remove-Item $zip, $tmp -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "Command-line tools déjà en place."
}

# -------------------------------------------- Plateforme 34 + build-tools
Write-Host "Installation platform-tools / android-34 / build-tools 34.0.0 (licences acceptées)..." -ForegroundColor Yellow
# 'y' en boucle pour accepter toutes les licences automatiquement
$yes = "y`n" * 30
$yes | & $CmdlineTools --sdk_root="$SdkRoot" --licenses | Out-Null
& $CmdlineTools --sdk_root="$SdkRoot" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# ------------------------------------------------------- local.properties
$LocalProps = Join-Path $RepoRoot "local.properties"
# Barres obliques : format accepté par Gradle, sans piège d'échappement.
$sdkDirProps = $SdkRoot -replace '\\', '/'
if (-not (Test-Path $LocalProps)) {
    $contenu = @"
sdk.dir=$sdkDirProps
# Renseigner les secrets du keystore release (ne JAMAIS committer ce fichier) :
iptv2StorePassword=A_REMPLIR
iptv2KeyPassword=A_REMPLIR
iptv2KeyAlias=iptv2
"@
    # SANS BOM : Set-Content -Encoding UTF8 ajoute un BOM que Java/Gradle ne
    # retire pas → la clé sdk.dir ne serait pas reconnue.
    [IO.File]::WriteAllText($LocalProps, $contenu)
    Write-Host "local.properties créé → renseigne les mots de passe du keystore." -ForegroundColor Yellow
} else {
    Write-Host "local.properties existe déjà : non modifié."
}

# ----------------------------------------------------------------- Bilan
Write-Host ""
Write-Host "=== Terminé ===" -ForegroundColor Green
Write-Host "Reste à faire manuellement :"
Write-Host "  1. Copier iptv2-release.jks dans app\ (il n'est pas versionné)."
Write-Host "  2. Renseigner iptv2StorePassword / iptv2KeyPassword dans local.properties."
Write-Host "  3. Builder :  gradlew.bat assembleRelease"
Write-Host "     L'APK sort dans app\build\outputs\apk\release\ et la tâche"
Write-Host "     publishReleaseToNicoUpdate le copie vers le dossier update"
Write-Host "     (IPTV2_UPDATE_DIR ou Z:\update-iptv2 par défaut)."
