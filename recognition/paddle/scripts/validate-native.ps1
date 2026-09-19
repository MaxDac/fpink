[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$AndroidSdk)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$ndk = Join-Path $AndroidSdk 'ndk\28.2.13676358'
$cmake = Join-Path $AndroidSdk 'cmake\3.22.1\bin\cmake.exe'
$ninja = Join-Path $AndroidSdk 'cmake\3.22.1\bin\ninja.exe'
$toolchain = Join-Path $ndk 'build\cmake\android.toolchain.cmake'
foreach ($required in @($cmake, $ninja, $toolchain)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Required workspace Android toolchain input is absent: $required" }
}
& (Join-Path $PSScriptRoot 'prepare.ps1') -VerifyOnly
$output = Join-Path $root 'build\native-validation'
& $cmake -S (Join-Path $root 'src\main\cpp') -B $output -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_NDK=$ndk" `
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'Android CMake configuration failed' }
& $cmake --build $output --parallel 2
if ($LASTEXITCODE -ne 0) { throw 'Android JNI compilation/link failed' }
& (Join-Path $PSScriptRoot 'verify-elf.ps1') -Path (Join-Path $output 'libfpink_paddle.so')
& (Join-Path $PSScriptRoot 'verify-elf.ps1') `
    -Path (Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so')
Write-Output 'Real Android ARM64 JNI linked against the pinned CPU runtime. Android DEVICE inference still requires instrumentation.'
