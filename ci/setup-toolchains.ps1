param([string]$CacheDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) '.native-tools'))
$ErrorActionPreference = 'Stop'
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'toolchain.json') -Raw | ConvertFrom-Json
$wrapper = Join-Path (Split-Path -Parent $PSScriptRoot) 'gradle/wrapper/gradle-wrapper.jar'
if ((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash.ToLowerInvariant() -ne $lock.gradle_wrapper_sha256) {
    throw 'Gradle wrapper does not match the official checksum recorded in the toolchain lock'
}
New-Item -ItemType Directory -Path $CacheDirectory -Force | Out-Null
foreach ($name in @('llvm_mingw', 'jdk')) {
    $package = $lock.$name
    $archive = Join-Path $CacheDirectory "$name.zip"
    if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.sha256) {
        Write-Host "Downloading pinned $name ($($package.version))"
        Invoke-WebRequest -Uri $package.url -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.sha256) { throw "Checksum mismatch: $name" }
    # Verify each download before extracting or running any tool from it.
    Expand-Archive -LiteralPath $archive -DestinationPath $CacheDirectory -Force
}
$java = Join-Path $CacheDirectory $lock.jdk.directory
$compiler = Join-Path $CacheDirectory "$($lock.llvm_mingw.directory)/bin/clang++.exe"
if (!(Test-Path -LiteralPath "$java/bin/java.exe") -or !(Test-Path -LiteralPath $compiler)) { throw 'Toolchain archive layout does not match lock file' }
[pscustomobject]@{ JavaHome = (Resolve-Path -LiteralPath $java).Path; Compiler = (Resolve-Path -LiteralPath $compiler).Path }
