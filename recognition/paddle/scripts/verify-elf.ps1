[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Path)

$ErrorActionPreference = 'Stop'
$bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x7f -or $bytes[1] -ne 0x45 -or
    $bytes[2] -ne 0x4c -or $bytes[3] -ne 0x46 -or $bytes[4] -ne 2 -or $bytes[5] -ne 1 -or
    [BitConverter]::ToUInt16($bytes, 18) -ne 183) {
    throw "Expected a little-endian AArch64 ELF64 library: $Path"
}
$offset = [BitConverter]::ToUInt64($bytes, 32)
$size = [BitConverter]::ToUInt16($bytes, 54)
$count = [BitConverter]::ToUInt16($bytes, 56)
$segments = @()
$dynamicOffset = 0L
$dynamicSize = 0L
for ($i = 0; $i -lt $count; $i++) {
    $p = [int]($offset + $i * $size)
    $type = [BitConverter]::ToUInt32($bytes, $p)
    if ($type -eq 1) {
        $segment = [pscustomobject]@{
            Offset = [BitConverter]::ToUInt64($bytes, $p + 8)
            Address = [BitConverter]::ToUInt64($bytes, $p + 16)
            Size = [BitConverter]::ToUInt64($bytes, $p + 32)
            Alignment = [BitConverter]::ToUInt64($bytes, $p + 48)
        }
        if ($segment.Alignment -lt 16384 -or $segment.Alignment % 16384 -ne 0 -or
            $segment.Offset % 16384 -ne $segment.Address % 16384) {
            throw "Not 16 KB page compatible: $Path"
        }
        $segments += $segment
    } elseif ($type -eq 2) {
        $dynamicOffset = [BitConverter]::ToUInt64($bytes, $p + 8)
        $dynamicSize = [BitConverter]::ToUInt64($bytes, $p + 32)
    }
}
if ($segments.Count -eq 0) { throw "ELF has no loadable segments: $Path" }
$needed = @()
$stringAddress = 0L
for ($p = [int]$dynamicOffset; $p -lt $dynamicOffset + $dynamicSize; $p += 16) {
    $tag = [BitConverter]::ToInt64($bytes, $p)
    $value = [BitConverter]::ToUInt64($bytes, $p + 8)
    if ($tag -eq 0) { break }
    if ($tag -eq 1) { $needed += $value }
    if ($tag -eq 5) { $stringAddress = $value }
}
$stringSegment = $segments | Where-Object {
    $_.Address -le $stringAddress -and $stringAddress -lt $_.Address + $_.Size
} | Select-Object -First 1
if (!$stringSegment) { throw "ELF string table is not mapped: $Path" }
$stringOffset = $stringSegment.Offset + $stringAddress - $stringSegment.Address
$names = foreach ($entry in $needed) {
    $start = [int]($stringOffset + $entry)
    $end = $start
    while ($end -lt $bytes.Length -and $bytes[$end] -ne 0) { $end++ }
    [Text.Encoding]::ASCII.GetString($bytes, $start, $end - $start)
}
$allowed = @('libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libstdc++.so',
    'libc++_shared.so', 'libpaddle_light_api_shared.so')
foreach ($name in $names) {
    if ($name -notin $allowed) { throw "Unreviewed native dependency '$name' in $Path" }
}
$sectionOffset = [BitConverter]::ToUInt64($bytes, 40)
$sectionSize = [BitConverter]::ToUInt16($bytes, 58)
$sectionCount = [BitConverter]::ToUInt16($bytes, 60)
for ($i = 0; $i -lt $sectionCount; $i++) {
    $header = [int]($sectionOffset + $i * $sectionSize)
    if ([BitConverter]::ToUInt32($bytes, $header + 4) -ne 11) { continue }
    $symbolOffset = [BitConverter]::ToUInt64($bytes, $header + 24)
    $symbolBytes = [BitConverter]::ToUInt64($bytes, $header + 32)
    $symbolSize = [BitConverter]::ToUInt64($bytes, $header + 56)
    $firstGlobal = [BitConverter]::ToUInt32($bytes, $header + 44)
    if ($symbolSize -ne 24 -or $symbolBytes % $symbolSize -ne 0) { throw 'Unexpected dynamic symbol table layout.' }
    for ($j = 0; $j -lt $symbolBytes / $symbolSize; $j++) {
        $local = ($bytes[[int]($symbolOffset + $j * $symbolSize + 4)] -shr 4) -eq 0
        if (($j -lt $firstGlobal) -ne $local) { throw "Invalid dynamic symbol binding boundary in $Path" }
    }
}
Write-Output "$([IO.Path]::GetFileName($Path)): AArch64; LOAD alignments $($segments.Alignment -join ', '); NEEDED $($names -join ', ')"
