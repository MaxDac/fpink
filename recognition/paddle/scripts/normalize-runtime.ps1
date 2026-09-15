[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputFile,
    [Parameter(Mandatory = $true)][string]$OutputFile
)

$ErrorActionPreference = 'Stop'
$sourceHash = '6558bf52fee978db21a550bb023378f865d145672751cccf84d0e797df60b369'
$resultHash = '3966a5d0ac2569ca63ca9cbd84093d5d23026eb85fa64e7a3062fa8aba89a9b6'
$inputPath = (Resolve-Path -LiteralPath $InputFile).Path
$outputPath = [IO.Path]::GetFullPath($OutputFile)
if ($inputPath -eq $outputPath) { throw 'Normalization must preserve the upstream input file.' }
if ((Get-Item -LiteralPath $inputPath).Length -ne 3729624 -or
    (Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash -ne $sourceHash) {
    throw 'Refusing to normalize an unrecognized upstream runtime.'
}
$source = [IO.File]::ReadAllBytes($inputPath)
$bytes = [byte[]]$source.Clone()
$sectionOffset = [BitConverter]::ToUInt64($source, 40)
$sectionSize = [BitConverter]::ToUInt16($source, 58)
$sectionCount = [BitConverter]::ToUInt16($source, 60)
$changedSections = 0
$patchOffset = -1
for ($i = 0; $i -lt $sectionCount; $i++) {
    $header = [int]($sectionOffset + $i * $sectionSize)
    if ([BitConverter]::ToUInt32($source, $header + 4) -ne 11) { continue }
    # ELF gABI: SHT_DYNSYM.sh_info is the index of the first non-STB_LOCAL symbol.
    $symbolOffset = [BitConverter]::ToUInt64($source, $header + 24)
    $symbolBytes = [BitConverter]::ToUInt64($source, $header + 32)
    $symbolSize = [BitConverter]::ToUInt64($source, $header + 56)
    if ($symbolSize -ne 24 -or $symbolBytes % $symbolSize -ne 0) { throw 'Unexpected ELF symbol layout.' }
    $firstNonLocal = -1
    for ($j = 0; $j -lt $symbolBytes / $symbolSize; $j++) {
        $binding = $source[[int]($symbolOffset + $j * $symbolSize + 4)] -shr 4
        if ($binding -ne 0 -and $firstNonLocal -lt 0) { $firstNonLocal = $j }
        if ($binding -eq 0 -and $firstNonLocal -ge 0) {
            throw 'Interleaved local symbols cannot be corrected by this normalization.'
        }
    }
    $patchOffset = $header + 44
    if ($patchOffset -ne 3728260 -or $firstNonLocal -ne 10 -or
        [BitConverter]::ToUInt32($source, $patchOffset) -ne 3) {
        throw 'Upstream ELF does not have the precisely reviewed sh_info defect.'
    }
    [BitConverter]::GetBytes([uint32]$firstNonLocal).CopyTo($bytes, $patchOffset)
    $changedSections++
}
if ($changedSections -ne 1) { throw 'Expected exactly one dynamic symbol table.' }

$programOffset = [BitConverter]::ToUInt64($source, 32)
$programSize = [BitConverter]::ToUInt16($source, 54)
$programCount = [BitConverter]::ToUInt16($source, 56)
$sha = [Security.Cryptography.SHA256]::Create()
try {
    for ($i = 0; $i -lt $programCount; $i++) {
        $header = [int]($programOffset + $i * $programSize)
        if ([BitConverter]::ToUInt32($source, $header) -ne 1) { continue }
        $offset = [int][BitConverter]::ToUInt64($source, $header + 8)
        $length = [int][BitConverter]::ToUInt64($source, $header + 32)
        if ($patchOffset -lt $offset + $length -and $patchOffset + 4 -gt $offset) {
            throw 'Normalization would touch a loadable segment.'
        }
        $before = [BitConverter]::ToString($sha.ComputeHash($source, $offset, $length))
        $after = [BitConverter]::ToString($sha.ComputeHash($bytes, $offset, $length))
        if ($before -ne $after) { throw 'Normalization changed a loadable segment.' }
    }
    $digest = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '')
    if ($digest -ne $resultHash) { throw 'Normalized runtime checksum is not the reviewed result.' }
} finally {
    $sha.Dispose()
}
[IO.File]::WriteAllBytes($outputPath, $bytes)
Write-Output 'Normalized .dynsym.sh_info 3 -> 10; all PT_LOAD bytes unchanged; exact source/result SHA-256 verified.'
