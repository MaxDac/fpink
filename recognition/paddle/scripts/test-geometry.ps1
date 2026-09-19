[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Cxx)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build\geometry_test.exe'
New-Item -ItemType Directory -Force (Split-Path $output -Parent) | Out-Null
& $Cxx -std=c++17 -Wall -Wextra -Werror "-I$(Join-Path $root 'src\main\cpp')" `
    (Join-Path $root 'src\main\cpp\ocr_geometry.cpp') `
    (Join-Path $root 'src\test\cpp\geometry_test.cpp') -o $output
if ($LASTEXITCODE -ne 0) { throw 'Native geometry compilation failed' }
$env:PATH = (Split-Path $Cxx -Parent) + ';' + $env:PATH
& $output
if ($LASTEXITCODE -ne 0) { throw 'Native geometry tests failed' }
