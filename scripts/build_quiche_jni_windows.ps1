<#
.SYNOPSIS
  Build the desktop-JVM JNI library for Windows: a static quiche.lib (Rust/cargo,
  MSVC toolchain) plus the libquiche_jni.dll wrapper (CMake). Runs natively on a
  Windows x86_64 host with MSVC + NASM available (BoringSSL needs NASM).

.PARAMETER Arch
  Target architecture. Only x86_64 is supported for now.

.OUTPUTS
  build/buildJniWindows/<arch>/libquiche_jni.dll
#>
param(
  [string]$Arch = "x86_64"
)

$ErrorActionPreference = "Stop"

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$QuicheDir   = Join-Path $ProjectRoot "third_party/quiche"
$SrcDir      = Join-Path $ProjectRoot "kiche/jni"

if ($Arch -ne "x86_64") { throw "Unsupported arch: $Arch (only x86_64 is supported)" }
$RustTarget = "x86_64-pc-windows-msvc"

$QuicheOut = Join-Path $ProjectRoot "build/quiche/windows/$Arch"
$JniOut    = Join-Path $ProjectRoot "build/buildJniWindows/$Arch"

Write-Host "Building quiche static lib for $RustTarget ..."
cargo build --manifest-path "$QuicheDir/quiche/Cargo.toml" --target $RustTarget --features ffi --release
if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }

New-Item -ItemType Directory -Force -Path (Join-Path $QuicheOut "lib"), (Join-Path $QuicheOut "include") | Out-Null
Copy-Item (Join-Path $QuicheDir "target/$RustTarget/release/quiche.lib") (Join-Path $QuicheOut "lib") -Force
Copy-Item (Join-Path $QuicheDir "quiche/include/quiche.h")              (Join-Path $QuicheOut "include") -Force

if (-not $env:JAVA_HOME) { Write-Warning "JAVA_HOME not set — CMake's FindJNI may fail to locate jni.h" }

New-Item -ItemType Directory -Force -Path $JniOut | Out-Null
cmake -B $JniOut -S $SrcDir `
  -DCMAKE_BUILD_TYPE=Release `
  -DJAVA_HOME="$env:JAVA_HOME" `
  -DQUICHE_INCLUDE_DIR="$QuicheOut/include" `
  -DQUICHE_LIB_PATH="$QuicheOut/lib/quiche.lib"
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }

cmake --build $JniOut --config Release
if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }

# MSVC is a multi-config generator: the dll lands under a Release/ subdir.
# Copy it to a predictable path so the workflow can stage it.
$dll = Get-ChildItem -Path $JniOut -Recurse -Filter "libquiche_jni.dll" | Select-Object -First 1
if (-not $dll) { throw "libquiche_jni.dll was not produced under $JniOut" }
Copy-Item $dll.FullName (Join-Path $JniOut "libquiche_jni.dll") -Force

Write-Host "Built $(Join-Path $JniOut 'libquiche_jni.dll')"
