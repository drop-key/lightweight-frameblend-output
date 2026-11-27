# Transparent release builds

The public GitHub Actions workflow is `.github/workflows/build.yml`. It runs on
push, pull request, and manual dispatch, with read-only repository permissions.
It needs no publishing token or repository secrets.

## Before resubmitting to Modrinth

1. Update the public source repository with this complete source tree, preserving
   its directories, including `.github/`, `ci/`, and `native/third_party/Spout/`.
   Remove the obsolete DLLs formerly stored in `src/main/resources/natives/win64/`
   and the old flat SDK files directly under `native/third_party/Spout/`. The SDK
   now uses the `SpoutGL`, `SpoutDX`, and `SpoutLibrary` subdirectories.
2. Open **Actions → Build release from source** and wait for a successful run.
   If GitHub requires workflow approval, enable the workflow in your repository.
3. Download that run's `lightweight-frameblend-output-<commit>` artifact. Extract
   it and upload its installable JAR to Modrinth. Use that exact file; do not
   substitute a separately built JAR or modify/recompress it.
4. Give moderators the public workflow-run URL and commit URL. The artifact
   contains `SHA256SUMS.txt`, the complete source ZIP, all three native DLLs,
   `BUILD-INFO.json`, and the pinned toolchain/source manifests. Moderators can
   compare the Modrinth JAR's SHA-256 with the artifact's checksum.

An unexecuted workflow is not evidence of a public build. Local checks do not
replace the successful public run or guarantee moderation approval. Address any
other moderation concerns separately before resubmitting.

## What the workflow verifies

- Java and LLVM-MinGW downloads are pinned by URL and SHA-256 in
  `ci/toolchain.json`. The Gradle wrapper JAR and Gradle distribution checksums
  are verified too. The Fabric Loom plugin uses a fixed release version.
- Every included DLL is compiled from source during the build. There are no
  precompiled DLL inputs to the release. A stale DLL accidentally left under
  `src/main/resources/` is excluded from packaging.
- Spout2's complete required source is included under `native/third_party/Spout/`.
  `UPSTREAM.json` maps each vendored file to its upstream revision/path and hash.
  All files are verified against that manifest before compilation.
- `SpoutLibrary.dll`, `fbo_spout_dx.dll`, and `fbo_spout_bridge.dll` are compiled
  twice into separate object/output directories. All three must match byte for
  byte. Each DLL extracted from the JAR must also match the rebuilt file.
- Native builds reject compilation-time date macros, omit PE build timestamps,
  and remap source paths to a stable prefix. Gradle archives use fixed timestamps
  and a stable file order.
- Normal CPU/shader/configuration tests run in hosted CI. GPU pixel/Spout tests
  require physical graphics hardware and are not claimed to run on the hosted
  runner.
- A headless Fabric compatibility probe runs the actual transformed Minecraft
  ring-buffer cleanup method. It verifies that other mods' mapping-release HEAD
  callbacks run before buffers close, with both higher and lower Mixin priorities,
  unequal buffer/fence capacities, and repeated cleanup. The probe is a separate
  test mod and is not bundled in the installable JAR.

These checks establish build provenance and matching bytes, not a proof that
arbitrary native code is safe. The source remains available for inspection.

## Local reproduction on Windows

Install PowerShell 7. From the project directory:

```powershell
$tools = ./ci/setup-toolchains.ps1
$env:JAVA_HOME = $tools.JavaHome
$env:FBO_NATIVE_COMPILER = $tools.Compiler
./gradlew.bat clean build releaseSources --no-daemon
./native/build.ps1 -BuildDirectory build/native-objects-second -OutputDirectory build/native-second
$jar = Get-ChildItem build/libs/*.jar | Where-Object { !$_.Name.EndsWith('-sources.jar') }
./ci/verify-native.ps1 -Jar $jar.FullName -CompareDirectory build/native-second
./ci/collect-release.ps1
```

Results are in `build/release/`. Bootstrap archives are verified before use;
compiler objects and DLLs are rebuilt without reusing repository-supplied native
binaries. For additional checks on a physical GPU, run:

```powershell
./gradlew.bat test -PverifyRecordingGpu verifySpoutTransport -PbenchmarkVulkanRecording -PverifyDirectX --no-daemon
```

The Java package, mod ID, settings schema and public Spout sender name are
unchanged by the release-build automation.
