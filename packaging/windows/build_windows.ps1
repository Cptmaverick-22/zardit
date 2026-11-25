param(
    [switch]$UseSystemTools
)

$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $RootDir

$PythonBin = "python"
$VenvPython = Join-Path $RootDir ".venv\Scripts\python.exe"
if (Test-Path $VenvPython) {
    $PythonBin = $VenvPython
}

& $PythonBin -m PyInstaller --version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller is required in $PythonBin (pip install pyinstaller)."
}

$Version = (& $PythonBin -c "import config; print(config.APP_VERSION)").Trim()
$CacheDir = Join-Path $RootDir "packaging\cache\win64"
$DistRoot = Join-Path $RootDir "dist"
$DistApp = Join-Path $DistRoot "Zardit"
$PortableZip = Join-Path $DistRoot "Zardit-$Version-win64-portable.zip"
$InstallerExe = Join-Path $DistRoot "Zardit-$Version-win64-setup.exe"

New-Item -ItemType Directory -Force -Path $CacheDir, $DistRoot | Out-Null
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $RootDir "build\zardit"), $DistApp
Remove-Item -Force -ErrorAction SilentlyContinue $PortableZip, $InstallerExe

Write-Host "==> Building Windows PyInstaller bundle for Zardit v$Version..."
& $PythonBin -m PyInstaller --noconfirm --clean (Join-Path $RootDir "packaging\windows\zardit.spec")
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller build failed."
}

if ($UseSystemTools) {
    Write-Host "==> Copying system ffmpeg.exe and deno.exe..."
    $FfmpegPath = (Get-Command ffmpeg.exe -ErrorAction Stop).Source
    $DenoPath = (Get-Command deno.exe -ErrorAction Stop).Source
    Copy-Item -Force $FfmpegPath (Join-Path $DistApp "ffmpeg.exe")
    Copy-Item -Force $DenoPath (Join-Path $DistApp "deno.exe")
} else {
    $FfmpegCache = Join-Path $CacheDir "ffmpeg.exe"
    if (-not (Test-Path $FfmpegCache)) {
        Write-Host "==> Downloading static FFmpeg (win64)..."
        $FfmpegZip = Join-Path $CacheDir "ffmpeg.zip"
        $FfmpegExtract = Join-Path $CacheDir "ffmpeg-extract"
        Invoke-WebRequest -Uri "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip" -OutFile $FfmpegZip
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $FfmpegExtract
        Expand-Archive -Path $FfmpegZip -DestinationPath $FfmpegExtract -Force
        $FfmpegBin = Get-ChildItem -Path $FfmpegExtract -Recurse -Filter "ffmpeg.exe" | Select-Object -First 1
        if (-not $FfmpegBin) {
            throw "Could not locate ffmpeg.exe inside downloaded archive."
        }
        Copy-Item -Force $FfmpegBin.FullName $FfmpegCache
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $FfmpegExtract, $FfmpegZip
    }
    Copy-Item -Force $FfmpegCache (Join-Path $DistApp "ffmpeg.exe")

    $DenoCache = Join-Path $CacheDir "deno.exe"
    if (-not (Test-Path $DenoCache)) {
        Write-Host "==> Downloading Deno (win64)..."
        $DenoZip = Join-Path $CacheDir "deno.zip"
        Invoke-WebRequest -Uri "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip" -OutFile $DenoZip
        Expand-Archive -Path $DenoZip -DestinationPath $CacheDir -Force
        Remove-Item -Force -ErrorAction SilentlyContinue $DenoZip
        if (-not (Test-Path $DenoCache)) {
            throw "Could not locate deno.exe inside downloaded archive."
        }
    }
    Copy-Item -Force $DenoCache (Join-Path $DistApp "deno.exe")
}

Copy-Item -Force (Join-Path $RootDir "LICENSE") (Join-Path $DistApp "LICENSE")

Write-Host "==> Smoke testing bundled CLI diagnostics..."
$CliExe = Join-Path $DistApp "zardit-cli.exe"
& $CliExe --diagnostics
if ($LASTEXITCODE -ne 0) {
    throw "Bundled zardit-cli.exe --diagnostics failed."
}

Write-Host "==> Creating portable archive $PortableZip..."
Compress-Archive -Path $DistApp -DestinationPath $PortableZip -Force

$IsccCandidates = @(
    (Get-Command ISCC.exe -ErrorAction SilentlyContinue | ForEach-Object { $_.Source }),
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { $_ -and (Test-Path $_) }

$Iscc = $IsccCandidates | Select-Object -First 1
if ($Iscc) {
    Write-Host "==> Building Windows installer with Inno Setup..."
    & $Iscc "/DMyAppVersion=$Version" (Join-Path $RootDir "packaging\windows\zardit.iss")
    if ($LASTEXITCODE -ne 0) {
        throw "Inno Setup compilation failed."
    }
    Write-Host "==> Built installer: $InstallerExe"
} else {
    Write-Warning "Inno Setup (ISCC.exe) not found; skipped setup .exe and built portable ZIP only."
}

Write-Host "==> Done."
