[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Cxx)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$compiler = (Get-Command -Name $Cxx -CommandType Application -ErrorAction Stop).Source
$sourceDirectory = [IO.Path]::Combine($root, 'src', 'main', 'cpp')
$testSource = [IO.Path]::Combine($root, 'src', 'test', 'cpp', 'geometry_test.cpp')
$outputName = if ([IO.Path]::DirectorySeparatorChar -eq '\') { 'geometry_test.exe' } else { 'geometry_test' }
$output = [IO.Path]::Combine($root, 'build', $outputName)
New-Item -ItemType Directory -Force (Split-Path $output -Parent) | Out-Null
$originalPath = $env:PATH
try {
    $env:PATH = (Split-Path $compiler -Parent) + [IO.Path]::PathSeparator + $originalPath
    & $compiler -std=c++17 -Wall -Wextra -Werror "-I$sourceDirectory" `
        (Join-Path $sourceDirectory 'ocr_geometry.cpp') $testSource -o $output
    if ($LASTEXITCODE -ne 0) { throw 'Native geometry compilation failed' }
    & $output
    if ($LASTEXITCODE -ne 0) { throw 'Native geometry tests failed' }
} finally {
    $env:PATH = $originalPath
}
