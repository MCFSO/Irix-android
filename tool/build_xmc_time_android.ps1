param(
    [string]$RustRepo = "D:\xmcserverlancher",
    [string]$Abi = "arm64-v8a",
    [string]$Ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.0.13004108",
    [string]$Api = "23"
)

$ErrorActionPreference = "Stop"

if ($Abi -ne "arm64-v8a") {
    throw "Only arm64-v8a is wired today. Add armv7/x86_64 mapping here and NDK clang names before using another ABI."
}

$target = "aarch64-linux-android"
$clang = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android$Api-clang.cmd"
$ar = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-ar.exe"
if (!(Test-Path $clang)) { throw "NDK clang not found: $clang" }
if (!(Test-Path $ar)) { throw "NDK llvm-ar not found: $ar" }

$env:CC_aarch64_linux_android = $clang
$env:AR_aarch64_linux_android = $ar
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang

Push-Location (Join-Path $RustRepo "rust")
try {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    cargo build --release --target $target -p xmc_time 2>&1 | Out-Host
    $code = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    if ($code -ne 0) { throw "cargo build failed ($code)" }
} finally {
    Pop-Location
}

$src = Join-Path $RustRepo "rust\target\$target\release\libxmc_time.so"
if (!(Test-Path $src)) { throw "Rust library not produced: $src" }

$dstDir = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\$Abi"
New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
Copy-Item $src (Join-Path $dstDir "libxmc_time.so") -Force

$size = (Get-Item (Join-Path $dstDir "libxmc_time.so")).Length
Write-Host "Copied libxmc_time.so ($size bytes) to $dstDir"
