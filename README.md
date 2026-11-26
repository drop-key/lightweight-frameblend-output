# Lightweight Frameblend Output

Lightweight Frameblend Output combines your recorded Minecraft frames into a smooth motion blur, which is sent through Spout2 to OBS. The motion blur is visible in streams/recordings, while keeping the game unaffected.

It is a modified fork of [Natural Motion Blur](https://modrinth.com/mod/natural-motion-blur), which stripped most of the features of the original mod, only keeping the OBS Output logic using frame blend.

Gripes I had with the original mod, were the immense performance trade-offs of using the OBS Output and the fact that you had to manually enable the Spout Output every time you restarted the game.

In comparison to the original project, this fork greatly improved the performance by having reduced blending work via a rolling average, reusing recording textures, publishing frames on a background thread and directly rendering textures into shared output removing an additional image copy. Compatible Vulkan systems can also share output directly with DirectX, bypassing the OpenGL bridge. Performance improvements depend on your hardware, drivers, and game workload.

The menu screen was overhauled and a setting was added to have the Spout2 Output automatically start at game launch.

### Performance

Development testing used an **RTX 4070 SUPER**, **1920×1080**, **Vulkan**, and **60 FPS OBS output**.

| Test | [Natural Motion Blur](https://modrinth.com/mod/natural-motion-blur) | [Lightweight Frameblend Output](https://modrinth.com/mod/frameblend-output) | Approximate improvement |
| :--- | ---: | ---: | ---: |
| OBS Output | 900–1200 FPS | 1600–1700 FPS | 1.3–1.9× |

*About 1900-2100 FPS without OBS Output.*

### Optimizations

| Area | Before optimization | Optimized approach |
| :--- | :--- | :--- |
| Frame blending | Repeatedly blend the full frame history | Update a rolling average |
| Average-buffer bandwidth | 16 bytes per pixel | 12 bytes per pixel — 25% less |
| Output copies | Separate copy into the shared image | Render directly into shared images during normal gameplay |
| Publishing | Sender work could stall rendering | Background publishing with nonblocking completion checks |
| Vulkan transport | Vulkan → OpenGL → DirectX | Vulkan → DirectX on compatible systems |

## Requirements and setup

Needs Windows,Fabric Loader, Fabric API, and OBS with the [Spout2 Capture](https://github.com/off-world-live/obs-spout2-plugin) plugin. Mod Menu is not required.

1. Open settings using /frameblend or /fbo.
2. Set the Output FPS the same as your OBS recording FPS.
3. Enable OBS output, then click Save & Done.
4. Add a [Spout2 Capture](https://github.com/off-world-live/obs-spout2-plugin) source in OBS then choose Frameblend Output.

In the FPS setting, the relevant item is the level of the Blur exposure, not the frame rate of Minecraft. This mod supports Minecraft natively using the support for the Vulkan API (disabled by default) supported by Minecraft. This mod is incompatible with the Vulkan Mod.
