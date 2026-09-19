[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OriginalRuntime,
    [Parameter(Mandatory = $true)][string]$Linker
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build\runtime-normalization-tests'
New-Item -ItemType Directory -Force $output | Out-Null
$normalized = Join-Path $output 'normalized.so'
$tampered = Join-Path $output 'tampered.so'
$shouldNotExist = Join-Path $output 'rejected-output.so'
if (Test-Path $shouldNotExist) { Remove-Item -LiteralPath $shouldNotExist }
$sourceHash = (Get-FileHash -LiteralPath $OriginalRuntime).Hash
$rawRejected = $false
try { & (Join-Path $PSScriptRoot 'verify-elf.ps1') -Path $OriginalRuntime }
catch { $rawRejected = $_.Exception.Message.Contains('Invalid dynamic symbol binding boundary') }
if (!$rawRejected) { throw 'Expected the original artifact to exhibit the documented ELF metadata defect.' }

& (Join-Path $PSScriptRoot 'normalize-runtime.ps1') -InputFile $OriginalRuntime -OutputFile $normalized
if ((Get-FileHash -LiteralPath $OriginalRuntime).Hash -ne $sourceHash) {
    throw 'Normalization modified its upstream input.'
}
$source = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $OriginalRuntime))
$result = [IO.File]::ReadAllBytes($normalized)
$changes = @()
for ($i = 0; $i -lt $source.Length; $i++) {
    if ($source[$i] -ne $result[$i]) { $changes += $i }
}
if ($changes.Count -ne 1 -or $changes[0] -ne 3728260 -or
    $source[3728260] -ne 3 -or $result[3728260] -ne 10) {
    throw 'Normalization made changes outside the reviewed sh_info byte.'
}
& (Join-Path $PSScriptRoot 'verify-elf.ps1') -Path $normalized
& $Linker -shared -m aarch64elf -o (Join-Path $output 'strict-link-probe.so') $normalized
if ($LASTEXITCODE -ne 0) { throw 'Strict AArch64 LLD rejected the corrected metadata.' }

$source[0] = $source[0] -bxor 1
[IO.File]::WriteAllBytes($tampered, $source)
$tamperRejected = $false
try { & (Join-Path $PSScriptRoot 'normalize-runtime.ps1') -InputFile $tampered -OutputFile $shouldNotExist }
catch { $tamperRejected = $_.Exception.Message.Contains('unrecognized upstream runtime') }
if (!$tamperRejected -or (Test-Path $shouldNotExist)) {
    throw 'An unknown runtime was not safely rejected.'
}
Remove-Item -LiteralPath $tampered
Write-Output 'Runtime normalization: original defect detected, exactly one byte changed, source preserved, strict LLD link passed, tampering rejected.'
