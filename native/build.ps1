param(
    [Parameter(Mandatory=$true)][string]$Compiler,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$compilerPath = (Resolve-Path -LiteralPath $Compiler).Path
$nativeRoot = $PSScriptRoot
$sdkRoot = Join-Path $nativeRoot 'third_party/Spout'
$buildRoot = Join-Path $nativeRoot '.build'
$scriptTime = (Get-Item -LiteralPath $PSCommandPath).LastWriteTimeUtc
New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
$headers = Get-ChildItem -LiteralPath $sdkRoot -Filter '*.h'
$headerTime = ($headers | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
$sources = @((Join-Path $nativeRoot 'FboDxSpout.cpp')) + @(Get-ChildItem -LiteralPath $sdkRoot -Filter '*.cpp' | ForEach-Object FullName)
$objects = @()
foreach ($source in $sources) {
    $object = Join-Path $buildRoot ([System.IO.Path]::GetFileNameWithoutExtension($source) + '.o')
    $objects += $object
    $sourceTime = (Get-Item -LiteralPath $source).LastWriteTimeUtc
    if (!(Test-Path -LiteralPath $object) -or (Get-Item -LiteralPath $object).LastWriteTimeUtc -lt $sourceTime -or (Get-Item -LiteralPath $object).LastWriteTimeUtc -lt $headerTime -or (Get-Item -LiteralPath $object).LastWriteTimeUtc -lt $scriptTime) {
        & $compilerPath -std=c++17 -O2 -mssse3 -DSPOUT_BUILD_STATIC "-ffile-prefix-map=$nativeRoot=frameblend-output/native" -Wno-unknown-pragmas -Wno-deprecated-declarations -I $sdkRoot -I "$JavaHome/include" -I "$JavaHome/include/win32" -c $source -o $object
        if ($LASTEXITCODE -ne 0) { throw "Native compile failed: $source" }
    }
}
$output = Join-Path $nativeRoot '../src/main/resources/natives/win64/fbo_spout_dx.dll'
& $compilerPath -shared -static '-Wl,--no-insert-timestamp' '-Wl,--strip-all' @objects -o $output -ld3d11 -ld3dcompiler_47 -ldxgi -ldxguid -lpsapi -lversion -lwinmm -lshell32 -lcomctl32 -lshlwapi -lole32 -luuid
if ($LASTEXITCODE -ne 0) { throw 'Native link failed' }
Write-Output "Built $output"
$glOutput = Join-Path $nativeRoot '../src/main/resources/natives/win64/fbo_spout_bridge.dll'
& $compilerPath -std=c++17 -O2 -shared -static -DSPOUT_BUILD_STATIC '-Wl,--no-insert-timestamp' '-Wl,--strip-all' -I "$JavaHome/include" -I "$JavaHome/include/win32" (Join-Path $nativeRoot 'FboGlSpout.cpp') -o $glOutput
if ($LASTEXITCODE -ne 0) { throw 'OpenGL bridge build failed' }
Write-Output "Built $glOutput"
