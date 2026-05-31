<#
.SYNOPSIS
  Build the Windows JNI wrapper `libquiche_jni.dll`, linked dynamically
  against a pre-built `libquiche.dll` via its import library. cargo is
  not invoked here.

.PARAMETER Arch
  Target architecture. Only x86_64 is supported for now.

.NOTES
  Required env vars:
    LIBQUICHE_JVM_NATIVE_ROOT — dir containing <arch>/libquiche.dll
                                (the .dll.lib import library must sit alongside)
    QUICHE_INCLUDE_DIR        — dir containing quiche.h

.OUTPUTS
  build/buildJniWindows/<arch>/libquiche_jni.dll
#>
param(
  [string]$Arch = "x86_64"
)

$ErrorActionPreference = "Stop"

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$SrcDir      = Join-Path $ProjectRoot "kiche/jni"

if ($Arch -ne "x86_64") { throw "Unsupported arch: $Arch (only x86_64 is supported)" }

if (-not $env:LIBQUICHE_JVM_NATIVE_ROOT) { throw "LIBQUICHE_JVM_NATIVE_ROOT must be set" }
if (-not $env:QUICHE_INCLUDE_DIR)        { throw "QUICHE_INCLUDE_DIR must be set" }

$LibquicheDir = Join-Path $env:LIBQUICHE_JVM_NATIVE_ROOT $Arch
$ImportLib    = Join-Path $LibquicheDir "libquiche.dll.lib"
if (-not (Test-Path $ImportLib)) {
  throw "Could not find $ImportLib (libquiche import library)"
}

$JniOut = Join-Path $ProjectRoot "build/buildJniWindows/$Arch"

if (-not $env:JAVA_HOME) { Write-Warning "JAVA_HOME not set — CMake's FindJNI may fail to locate jni.h" }

New-Item -ItemType Directory -Force -Path $JniOut | Out-Null
cmake -B $JniOut -S $SrcDir `
  -DCMAKE_BUILD_TYPE=Release `
  -DJAVA_HOME="$env:JAVA_HOME" `
  -DQUICHE_INCLUDE_DIR="$env:QUICHE_INCLUDE_DIR" `
  -DQUICHE_LIB_PATH="$ImportLib"
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }

cmake --build $JniOut --config Release
if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }

# MSVC is a multi-config generator: the dll lands under a Release/ subdir.
# Copy it to a predictable path so the workflow can stage it.
$dll = Get-ChildItem -Path $JniOut -Recurse -Filter "libquiche_jni.dll" | Select-Object -First 1
if (-not $dll) { throw "libquiche_jni.dll was not produced under $JniOut" }
Copy-Item $dll.FullName (Join-Path $JniOut "libquiche_jni.dll") -Force

Write-Host "Built $(Join-Path $JniOut 'libquiche_jni.dll')"
