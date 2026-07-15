# Windows convenience script: build the addon and copy it into your Minecraft mods folder in one step. On
# other platforms (or without this script) just run `./gradlew build` and drop build/libs/boss-pvp-1.0.0.jar
# into your .minecraft/mods/ folder manually. See BUILD.md for the portable instructions.
<#
.SYNOPSIS
    Build the Boss's PVP addon with JDK 25 and deploy it into the active Minecraft mods folder.

.DESCRIPTION
    Rebuilds boss-pvp-1.0.0.jar via gradlew, locates the mods folder that contains the
    AUTISM client jar, removes any old boss-pvp*.jar, and copies in the fresh build.

    Run from anywhere:  powershell -ExecutionPolicy Bypass -File .\deploy.ps1
    Or:                 .\deploy.ps1
    Skip the rebuild:   .\deploy.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    # Prefer JAVA_HOME if set; otherwise adjust this fallback to your local JDK 25 install.
    [string]$JdkHome    = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot" }),
    # Standard Fabric mods folder; auto-detected below if this one has no AUTISM jar.
    [string]$ModsFolder = "$env:APPDATA\.minecraft\mods",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

# Project root = the folder this script lives in (has gradlew + build\libs).
$ProjectDir = $PSScriptRoot
$JarName    = "boss-pvp-1.0.0.jar"
$BuiltJar   = Join-Path $ProjectDir "build\libs\$JarName"
$AddonGlob  = "boss-pvp*.jar"   # any version of our addon

# --- 1. Build -------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host "[1/5] Building $JarName with JDK 25..." -ForegroundColor Cyan
    if (-not (Test-Path $JdkHome)) { throw "JDK not found at: $JdkHome" }
    $env:JAVA_HOME = $JdkHome
    Push-Location $ProjectDir
    try {
        & "$ProjectDir\gradlew.bat" build --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "gradlew build failed (exit $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[1/5] Skipping build (-SkipBuild)." -ForegroundColor DarkGray
}

if (-not (Test-Path $BuiltJar)) { throw "Built jar not found: $BuiltJar" }

# --- 2. Locate the active mods folder (must contain an 'autism' jar) -------
function Test-ModsFolder([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path $path)) { return $false }
    return [bool](Get-ChildItem -Path $path -Filter "*autism*.jar" -File -ErrorAction SilentlyContinue |
                  Select-Object -First 1)
}

Write-Host "[2/5] Locating mods folder with the AUTISM client jar..." -ForegroundColor Cyan
$mods = $null
if (Test-ModsFolder $ModsFolder) {
    $mods = $ModsFolder
} else {
    Write-Host "      Default folder missing/has no autism jar; searching..." -ForegroundColor Yellow
    $searchRoots = @(
        $env:APPDATA,
        (Join-Path $env:APPDATA ".minecraft"),
        "$env:USERPROFILE\AppData\Roaming\.minecraft",
        "$env:USERPROFILE\curseforge\minecraft\Instances",
        "$env:APPDATA\com.modrinth.theseus\profiles",
        "$env:APPDATA\PrismLauncher\instances",
        "$env:APPDATA\.minecraft\versions"
    ) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique

    foreach ($root in $searchRoots) {
        $candidate = Get-ChildItem -Path $root -Directory -Recurse -Filter "mods" -ErrorAction SilentlyContinue |
                     Where-Object { Test-ModsFolder $_.FullName } |
                     Select-Object -First 1
        if ($candidate) { $mods = $candidate.FullName; break }
    }
}
if (-not $mods) { throw "Could not find a mods folder containing an 'autism' jar." }
Write-Host "      Using mods folder: $mods" -ForegroundColor Green

# --- Guard: refuse to deploy while the Minecraft GAME is running (it locks the jar) -
# Only the game JVM holds a lock on the mods jar. The MS Store build runs it as 'javaw'/'java' under a
# WindowsApps/Packages/runtime path. We deliberately IGNORE 'Minecraft.exe' (that's just the launcher /
# helper processes, which can stay open and do NOT lock the jar). As a final check we also verify the
# jar isn't actually locked before deleting it.
$mcProcs = Get-Process -Name 'javaw','java' -ErrorAction SilentlyContinue | Where-Object {
    $_.Path -match 'minecraft|\.minecraft|WindowsApps|Packages|runtime'
}
$targetJar = Join-Path $mods $JarName
$jarLocked = $false
if (Test-Path $targetJar) {
    try { $fs = [System.IO.File]::Open($targetJar,'Open','ReadWrite','None'); $fs.Close() }
    catch { $jarLocked = $true }
}
if ($mcProcs -or $jarLocked) {
    $ids = ($mcProcs | Select-Object -ExpandProperty Id) -join ', '
    throw "Minecraft GAME appears to be RUNNING (jvm pids: $ids; jarLocked=$jarLocked). Close the game fully (the launcher can stay open), then re-run .\deploy.ps1 (the built jar is ready in build\libs)."
}

# --- 3. Remove old copies of our addon ------------------------------------
Write-Host "[3/5] Removing old $AddonGlob ..." -ForegroundColor Cyan
$old = Get-ChildItem -Path $mods -Filter $AddonGlob -File -ErrorAction SilentlyContinue
foreach ($f in $old) {
    Write-Host "      - removing $($f.Name)" -ForegroundColor DarkGray
    Remove-Item $f.FullName -Force
}

# --- 4. Copy the fresh build ----------------------------------------------
Write-Host "[4/5] Copying fresh jar into mods folder..." -ForegroundColor Cyan
Copy-Item $BuiltJar -Destination (Join-Path $mods $JarName) -Force

# --- 5. Report -------------------------------------------------------------
Write-Host "[5/5] Deployed. Addon jar(s) now in the mods folder:" -ForegroundColor Cyan
Write-Host "      Mods folder: $mods" -ForegroundColor Green
Get-ChildItem -Path $mods -Filter $AddonGlob -File |
    Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
