param(
    [Parameter(Mandatory=$true)][string]$Jar,
    [string]$NativeDirectory = 'build/generated-resources/natives/win64',
    [string]$CompareDirectory
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$names = @('SpoutLibrary.dll', 'fbo_spout_bridge.dll', 'fbo_spout_dx.dll')
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Jar).Path)
try {
    $entries = @($archive.Entries | Where-Object { $_.FullName.EndsWith('.dll', [StringComparison]::OrdinalIgnoreCase) })
    if ($entries.Count -ne $names.Count) { throw 'The JAR contains an unexpected set of native libraries' }
    foreach ($name in $names) {
        $entry = $archive.GetEntry("natives/win64/$name")
        if (!$entry) { throw "Missing packaged DLL: $name" }
        $stream = $entry.Open()
        $hash = [System.Security.Cryptography.SHA256]::Create()
        try { $embedded = [Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant() }
        finally { $hash.Dispose(); $stream.Dispose() }
        $rebuilt = (Get-FileHash -LiteralPath (Join-Path $NativeDirectory $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($embedded -ne $rebuilt) { throw "Packaged DLL differs from its rebuilt source: $name" }
        if ($CompareDirectory) {
            $independent = (Get-FileHash -LiteralPath (Join-Path $CompareDirectory $name) -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($independent -ne $rebuilt) { throw "Independent native rebuild differs: $name" }
        }
        Write-Host "$embedded  $name -- packaged and rebuilt bytes match"
    }
} finally { $archive.Dispose() }
