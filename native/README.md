# Frameblend Output native bridges

`FboDxSpout.cpp` publishes shared Vulkan textures through DirectX 11 using GPU timeline fences and nonblocking reuse checks. Its integer texel copy preserves pixel values and image orientation.

`FboGlSpout.cpp` supplies the OpenGL and readback publisher through the bundled SpoutLibrary COM-like interface. All calls run with the publisher's OpenGL context current.

Use JDK 25 and LLVM-MinGW 20261117 (Windows x86_64 UCRT). From the repository root:

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk-25'
./native/build.ps1 -Compiler 'C:/path/to/llvm-mingw/bin/clang++.exe'
./gradlew.bat build
```

The script writes `fbo_spout_dx.dll` and `fbo_spout_bridge.dll` into `src/main/resources/natives/win64/`. Object files are cached in `native/.build/`.

The selected unmodified Spout2 sources and interface header are vendored under `third_party/Spout/`, from revision 2bd42d21cd1825b2fd61224d914c555e59545ab5. Copyright and license notices are retained. Runtime notices are bundled under `src/main/resources/META-INF/licenses/`.

The native verification task uses separate FBO probe sender names and does not modify OBS settings.
