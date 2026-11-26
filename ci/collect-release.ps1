param([string]$Destination = 'build/release')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $jar = @(Get-ChildItem -LiteralPath 'build/libs' -Filter '*.jar' | Where-Object { !$_.Name.EndsWith('-sources.jar') })
    if ($jar.Count -ne 1) { throw 'Expected one installable JAR; run a clean build first' }
    & (Join-Path $PSScriptRoot 'verify-native.ps1') -Jar $jar[0].FullName
    Copy-Item -LiteralPath $jar[0].FullName -Destination $Destination
    $source = @(Get-ChildItem -LiteralPath 'build/distributions' -Filter '*-source.zip')
    if ($source.Count -ne 1) { throw 'Expected the complete release source archive' }
    Copy-Item -LiteralPath $source[0].FullName -Destination $Destination
    foreach ($name in @('SpoutLibrary.dll', 'fbo_spout_bridge.dll', 'fbo_spout_dx.dll')) {
        Copy-Item -LiteralPath "build/generated-resources/natives/win64/$name" -Destination $Destination
    }
    Copy-Item -LiteralPath 'ci/toolchain.json' -Destination $Destination
    Copy-Item -LiteralPath 'native/third_party/Spout/UPSTREAM.json' -Destination (Join-Path $Destination 'SPOUT-SOURCES.json')
    $commitOutput = & git rev-parse HEAD 2>$null
    $commit = if ($LASTEXITCODE -eq 0) { ($commitOutput | Out-String).Trim() } else { $null }
    $dirty = if ($commit) { ![string]::IsNullOrWhiteSpace((& git status --porcelain | Out-String)) } else { $null }
    $runUrl = if ($env:GITHUB_RUN_ID) { "https://github.com/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID" } else { $null }
    [ordered]@{
        repository = $env:GITHUB_REPOSITORY
        source_commit = $commit
        working_tree_modified = $dirty
        workflow_run = $runUrl
        java_version = (& "$env:JAVA_HOME/bin/java.exe" -version 2>&1 | Out-String).Trim()
        compiler_version = (& $env:FBO_NATIVE_COMPILER --version | Out-String).Trim()
        packaged_native_verification = 'Every packaged DLL was compared byte-for-byte by SHA-256 against its rebuilt file.'
        native_gpu_tests = 'Not run on hosted CI; require a physical GPU. See local validation separately.'
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Destination 'BUILD-INFO.json') -Encoding utf8
    $lines = @(Get-ChildItem -LiteralPath $Destination -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } | Sort-Object Name | ForEach-Object {
        "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
    })
    $lines | Set-Content -LiteralPath (Join-Path $Destination 'SHA256SUMS.txt') -Encoding utf8
    $lines | Write-Host
} finally { Pop-Location }
