[CmdletBinding()]
param([switch]$VerifyOnly, [switch]$SourceBuild)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$manifest = Get-Content (Join-Path $root 'artifacts.lock.json') -Raw | ConvertFrom-Json
$licenses = Get-Content (Join-Path $root 'licenses.lock.json') -Raw | ConvertFrom-Json
$sourceRuntime = Get-Content (Join-Path $root 'source-runtime.lock.json') -Raw | ConvertFrom-Json

function Assert-Artifact([string]$Path, [long]$Length, [string]$Sha256) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf) -or
        ($Length -ge 0 -and (Get-Item -LiteralPath $Path).Length -ne $Length) -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Missing or corrupt pinned artifact: $Path"
    }
}

if (!$VerifyOnly) {
    if ($SourceBuild) {
        # The runtime and headers are checked in; this rebuilds them from the pinned Paddle Lite source.
        & bash (Join-Path $PSScriptRoot 'build-runtime.sh')
        if ($LASTEXITCODE -ne 0) { throw 'Paddle Lite source build failed' }
    }
    $cache = Join-Path $root 'build\acquisition'
    New-Item -ItemType Directory -Force $cache | Out-Null
    foreach ($archive in $manifest.archives) {
        $download = Join-Path $cache $archive.name
        if (!(Test-Path -LiteralPath $download)) {
            # Deliberate developer/build-time acquisition only. Never called by the Android app or Gradle.
            & curl.exe --fail --location --silent --show-error --proto '=https' --proto-redir '=https' `
                $archive.url --output $download
            if ($LASTEXITCODE -ne 0) { throw "Download failed: $($archive.name)" }
        }
        Assert-Artifact $download $archive.bytes $archive.sha256
        foreach ($artifact in $archive.files) {
            & tar -xf $download -C $cache $artifact.member
            if ($LASTEXITCODE -ne 0) { throw "Extraction failed: $($artifact.member)" }
            $source = Join-Path $cache ($artifact.member.Replace('/', '\'))
            Assert-Artifact $source $artifact.bytes $artifact.sha256
            $destination = Join-Path $root ($artifact.destination.Replace('/', '\'))
            New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
            Copy-Item -LiteralPath $source -Destination $destination -Force
        }
    }
}

foreach ($license in $licenses) {
    $destination = Join-Path $root "src\main\assets\paddle\licenses\$($license.name)"
    if (!$VerifyOnly -and !(Test-Path -LiteralPath $destination)) {
        New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
        & curl.exe --fail --location --silent --show-error --proto '=https' --proto-redir '=https' `
            $license.url --output $destination
        if ($LASTEXITCODE -ne 0) { throw "License download failed: $($license.name)" }
    }
    Assert-Artifact $destination $license.bytes $license.sha256
}

foreach ($archive in $manifest.archives) {
    foreach ($artifact in $archive.files) {
        Assert-Artifact (Join-Path $root ($artifact.destination.Replace('/', '\'))) $artifact.bytes $artifact.sha256
    }
}
foreach ($pin in $sourceRuntime.build.expectedSha256.PSObject.Properties) {
    Assert-Artifact (Join-Path $root ($pin.Name.Replace('/', '\'))) -1 $pin.Value
}

& (Join-Path $PSScriptRoot 'verify-elf.ps1') `
    -Path (Join-Path $root 'native\arm64-v8a\libpaddle_light_api_shared.so')

Write-Output 'Verified 2 real NB models, matching v5 dictionary, licenses, and the source-built ARM64 CPU runtime and headers. No network used in VerifyOnly mode.'
