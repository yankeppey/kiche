<#
.SYNOPSIS
  Build libquiche.{lib,dll,dll.lib} for Windows via cargo (MSVC + NASM).

.PARAMETER Arch
  Target architecture. Only x86_64 is supported for now.

.OUTPUTS
  build/quiche/windows/<arch>/libquiche.dll
  build/quiche/windows/<arch>/libquiche.dll.lib  (import library)
  build/quiche/windows/<arch>/quiche.lib         (static library, optional)
  build/quiche/windows/<arch>/include/quiche.h
  Flat layout — matches LIBQUICHE_JVM_NATIVE_ROOT convention used by
  build_quiche_jni_windows.ps1.
#>
param(
  [string]$Arch = "x86_64"
)

$ErrorActionPreference = "Stop"

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$QuicheDir   = Join-Path $ProjectRoot "third_party/quiche"

if ($Arch -ne "x86_64") { throw "Unsupported arch: $Arch (only x86_64 is supported)" }
$RustTarget = "x86_64-pc-windows-msvc"

$OutDir = Join-Path $ProjectRoot "build/quiche/windows/$Arch"

Write-Host "Building libquiche for $RustTarget ..."
cargo build --manifest-path "$QuicheDir/quiche/Cargo.toml" --target $RustTarget --features ffi --release
if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }

New-Item -ItemType Directory -Force -Path $OutDir, (Join-Path $OutDir "include") | Out-Null

# Static lib + dynamic lib + import library (needed at MSVC link time when
# something links against the DLL).
$cargoRelease = Join-Path $QuicheDir "target/$RustTarget/release"
Copy-Item (Join-Path $cargoRelease "quiche.lib")    $OutDir -Force
Copy-Item (Join-Path $cargoRelease "libquiche.dll") $OutDir -Force
$importLib = Join-Path $cargoRelease "libquiche.dll.lib"
if (Test-Path $importLib) {
  Copy-Item $importLib $OutDir -Force
} else {
  # Some cargo versions emit the import library as `libquiche.lib` alongside
  # the DLL (separate from the staticlib output `quiche.lib`).
  $altImport = Join-Path $cargoRelease "libquiche.lib"
  if (Test-Path $altImport) {
    Copy-Item $altImport (Join-Path $OutDir "libquiche.dll.lib") -Force
  } else {
    throw "Could not locate libquiche import library next to libquiche.dll under $cargoRelease"
  }
}
Copy-Item (Join-Path $QuicheDir "quiche/include/quiche.h") (Join-Path $OutDir "include") -Force

Write-Host "Built libquiche.{lib,dll,dll.lib} -> $OutDir/"
