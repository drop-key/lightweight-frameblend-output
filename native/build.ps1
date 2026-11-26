param(
    [string]$Compiler = $env:FBO_NATIVE_COMPILER,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputDirectory,
    [string]$BuildDirectory
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (!$Compiler) {
    $tools = & (Join-Path $repoRoot 'ci/setup-toolchains.ps1')
    $Compiler = $tools.Compiler
    if (!$JavaHome) { $JavaHome = $tools.JavaHome }
}
$compilerPath = (Resolve-Path -LiteralPath $Compiler).Path
$compilerRoot = Split-Path -Parent (Split-Path -Parent $compilerPath)
if (!(Test-Path -LiteralPath "$JavaHome/include/jni.h")) { throw 'Set JAVA_HOME to JDK 25.' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'build/generated-resources/natives/win64' }
if (!$BuildDirectory) { $BuildDirectory = Join-Path $repoRoot 'build/native-objects' }
New-Item -ItemType Directory -Path $BuildDirectory, $OutputDirectory -Force | Out-Null
$BuildDirectory = (Resolve-Path -LiteralPath $BuildDirectory).Path
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$sdk = Join-Path $PSScriptRoot 'third_party/Spout'
$upstream = Get-Content -LiteralPath (Join-Path $sdk 'UPSTREAM.json') -Raw | ConvertFrom-Json
foreach ($entry in $upstream.files.PSObject.Properties) {
    $source = Join-Path $sdk $entry.Name
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value.sha256) {
        throw "Vendored Spout source does not match its pinned manifest: $($entry.Name)"
    }
}
$flags = @('-std=c++17', '-O2', '-mssse3', '-DSPOUT_BUILD_STATIC', '-Wno-unknown-pragmas', '-Wno-deprecated-declarations', '-Werror=date-time',
    "-ffile-prefix-map=$repoRoot=frameblend-output", "-ffile-prefix-map=$compilerRoot=llvm-mingw", "-ffile-prefix-map=$JavaHome=jdk", '-I', "$sdk/SpoutGL", '-I', "$sdk/SpoutDX", '-I', "$sdk/SpoutLibrary", '-I', "$JavaHome/include", '-I', "$JavaHome/include/win32")
$commonNames = @('SpoutCopy', 'SpoutDirectX', 'SpoutFrameCount', 'SpoutSenderNames', 'SpoutSharedMemory', 'SpoutUtils')
$glNames = @('Spout', 'SpoutGL', 'SpoutGLextensions', 'SpoutReceiver', 'SpoutSender')
$units = [ordered]@{}
foreach ($name in ($commonNames + $glNames)) { $units[$name] = "$sdk/SpoutGL/$name.cpp" }
$units['SpoutDX'] = "$sdk/SpoutDX/SpoutDX.cpp"
$units['SpoutLibrary'] = "$sdk/SpoutLibrary/SpoutLibrary.cpp"
$units['FboDxSpout'] = Join-Path $PSScriptRoot 'FboDxSpout.cpp'
$units['FboGlSpout'] = Join-Path $PSScriptRoot 'FboGlSpout.cpp'
# Every translation unit is recompiled. Release builds never reuse opaque DLLs
# or object files supplied in the repository.
foreach ($name in $units.Keys) {
    Write-Host "Compiling $name.cpp"
    & $compilerPath @flags -c $units[$name] -o (Join-Path $BuildDirectory "$name.o")
    if ($LASTEXITCODE -ne 0) { throw "Native compile failed: $name" }
}
$libraries = @('-ld3d11', '-ldxgi', '-ldxguid', '-lpsapi', '-lversion', '-lwinmm', '-lshell32', '-lcomctl32', '-lshlwapi', '-lole32', '-luuid', '-lopengl32', '-lgdi32', '-ladvapi32', '-loleaut32')
function Link-Dll([string]$Name, [string[]]$Units, [string[]]$Extra = @()) {
    $objects = @($Units | ForEach-Object { Join-Path $BuildDirectory "$_.o" })
    & $compilerPath -shared -static '-Wl,--no-insert-timestamp' '-Wl,--strip-all' @objects @Extra @libraries -o (Join-Path $OutputDirectory $Name)
    if ($LASTEXITCODE -ne 0) { throw "Native link failed: $Name" }
    Write-Host "Built $Name"
}
Link-Dll 'fbo_spout_dx.dll' ($commonNames + @('SpoutDX', 'FboDxSpout')) @('-ld3dcompiler_47')
Link-Dll 'fbo_spout_bridge.dll' @('FboGlSpout')
Link-Dll 'SpoutLibrary.dll' ($commonNames + $glNames + @('SpoutLibrary')) @((Join-Path $PSScriptRoot 'SpoutLibrary.def'))
Get-ChildItem -LiteralPath $OutputDirectory -Filter '*.dll' | Get-FileHash -Algorithm SHA256 | Format-Table -AutoSize
