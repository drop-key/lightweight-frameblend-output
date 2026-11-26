# Frameblend Output native code

All three native libraries are built from the source in this directory:

- `FboDxSpout.cpp`: direct Vulkan/DirectX Spout publisher.
- `FboGlSpout.cpp`: JNI OpenGL/readback bridge through SpoutLibrary's COM-like interface.
- `third_party/Spout/SpoutLibrary/SpoutLibrary.cpp` and `SpoutGL/`: the SpoutLibrary implementation. No precompiled SpoutLibrary DLL is used.

The DirectX publisher also uses `third_party/Spout/SpoutDX/SpoutDX.cpp` and the common Spout sources. The SDK files are unmodified copies from revision 2bd42d21cd1825b2fd61224d914c555e59545ab5 of leadedge/Spout2. `third_party/Spout/UPSTREAM.json` records the upstream paths and SHA-256 hashes. Existing copyright and license notices are retained.

`build.ps1` compiles every required translation unit and links all three DLLs. Its default output is `build/generated-resources/natives/win64/`. Gradle runs this script before packaging resources. Objects are generated under `build/` and are never used as release inputs from the repository.

See [BUILDING.md](../BUILDING.md) for the public GitHub Actions workflow, pinned toolchain bootstrap, independent rebuild checks, and exact packaged-DLL verification. The native verification task uses separate FBO sender names and does not modify OBS settings.
