package net.frameblend.output.recording;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import net.frameblend.output.FrameblendOutputMod;
import net.frameblend.output.config.ConfigEntries;
import net.frameblend.output.config.ConfigManager;
import net.frameblend.output.mixin.PostChainAccessor;
import net.frameblend.output.mixin.PostPassAccessor;
import net.frameblend.output.mixin.ShaderManagerAccessor;
import net.frameblend.output.util.ClientRenderTargets;
import net.frameblend.output.util.GpuBufferUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecordingShaderManager {

    private static final int FRAME_BLEND_UBO_SIZE = 112;
    private static final int CURSOR_UBO_SIZE = 32;
    private static final int MAX_BATCH_SAMPLES = 24;
    private static final int GL_BATCH_LIMIT = 12;
    private static final int UBO_RING_SIZE = 3;

    private static final String FRAME_BLEND_UBO = "FrameBlendParamsUniforms";

    private static final String[] SAMPLE_NAMES = new String[MAX_BATCH_SAMPLES];
    static {
        for (int i = 0; i < MAX_BATCH_SAMPLES; i++) SAMPLE_NAMES[i] = "Sample" + i;
    }

    private static GraphicsResourceAllocator savedAllocator = null;

    private static RenderTarget recordingTarget = null;
    private static RenderTarget sumReadTarget = null;
    private static RenderTarget sumWriteTarget = null;
    private static final ExposureFrameHistory<RenderTarget> frameHistory = new ExposureFrameHistory<>();
    private static final RollingExposure<RenderTarget> rollingExposure = new RollingExposure<>();
    private static final MutableTextureInput[] recHistoryInputs = new MutableTextureInput[MAX_BATCH_SAMPLES];
    private static final float[] batchWeights = new float[MAX_BATCH_SAMPLES];
    private static MutableTextureInput previousSumInput;
    private static final Identifier OUTPUT_TARGET_ID = Identifier.fromNamespaceAndPath(FrameblendOutputMod.ID, "recording_output");
    private static final Set<Identifier> OUTPUT_TARGETS = Set.of(PostChain.MAIN_TARGET_ID, OUTPUT_TARGET_ID);
    private static int lastW = 0;
    private static int lastH = 0;

    private static PostChain recCombineChain = null;
    private static PostChain recDirectChain = null;
    private static PostChain resolveChain = null;
    private static final GpuBuffer[] recCombineUBORing = new GpuBuffer[UBO_RING_SIZE];
    private static int recCombineUBOIndex = 0;

    private static PostChain recCursorChain = null;
    private static GpuBuffer recCursorUBO   = null;

    private static final Identifier CURSOR_TEXTURE_ID =
            Identifier.fromNamespaceAndPath(FrameblendOutputMod.ID, "textures/gui/obs_cursor.png");
    private static PostPass.Input recCursorTextureInput = null;

    private static float   recPrevRawCursorX       = 0;
    private static float   recPrevRawCursorY       = 0;
    private static boolean recPrevRawCursorVisible = false;

    private static long    cachedGlfwHandle        = 0;
    private static boolean glfwHandleResolved      = false;

    private static boolean warnedCaptureFailure    = false;
    private static boolean loggedCapturePhase;
    private static long    retryCaptureAfterNanos  = 0L;
    private static long    pauseCaptureUntilNanos  = 0L;
    private static boolean resourceReloadActive    = false;

    public static void captureAllocator(GraphicsResourceAllocator allocator) {
        savedAllocator = allocator;
    }

    public static void captureFinalFrameAndPresent() {
        captureFinalFrameAndPresent(false);
    }

    public static void onFrameSubmitted() {
        try { SpoutFrameSender.onFrameSubmitted(); }
        catch (Throwable failure) { handleCaptureFailure(failure); }
    }

    public static void captureFinalFrameAndPresent(boolean beforeSubmission) {
        long now = System.nanoTime();
        if (resourceReloadActive || (pauseCaptureUntilNanos > 0L && now < pauseCaptureUntilNanos)) {
            savedAllocator = null;
            return;
        }
        if (pauseCaptureUntilNanos > 0L) {
            pauseCaptureUntilNanos = 0L;
        }
        if (retryCaptureAfterNanos > 0L && now < retryCaptureAfterNanos) {
            savedAllocator = null;
            return;
        }

        try {
            captureFinalFrameAndPresentInternal(beforeSubmission);
        } catch (Throwable t) {
            handleCaptureFailure(t);
        }
    }

    private static void captureFinalFrameAndPresentInternal(boolean beforeSubmission) {
        ConfigEntries cfg = ConfigManager.getConfig();
        if (!cfg.recordingOverlayEnabled) {
            savedAllocator = null;
            return;
        }
        if (!loggedCapturePhase) {
            loggedCapturePhase = true;
            System.out.println(beforeSubmission ? "[FBO] Capture is recorded before Minecraft frame submission."
                    : "[FBO] Capture is using the legacy tick-tail hook.");
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = ClientRenderTargets.getMain(mc);
        int w = main.width;
        int h = main.height;
        if (w <= 0 || h <= 0) {
            savedAllocator = null;
            return;
        }

        if (savedAllocator == null) {
            if (mc.level == null) {
                captureMenuFrameAndPresent(mc, main, w, h);
            }
            return;
        }

        long timingStart = CaptureTimings.begin();
        if (timingStart != 0L) CaptureGpuTimings.begin();
        RecordingOutputFrame direct = null;
        try {
            ensureTargets(w, h);
            long now = System.nanoTime();
            int targetHz = Math.max(1, cfg.recordingOverlayTargetFPS);
            long exposureNanos = 1_000_000_000L / targetHz;
            ExposureFrameHistory.Frame<RenderTarget> frame = frameHistory.capture(now, exposureNanos,
                    RecordingShaderManager::newSourceFrameTarget, true);
            boolean cursorVisible = getCursorState(mc, w, h).visible;
            boolean canRoll = !cursorVisible && RollingExposureRenderer.available()
                    && rollingExposure.prepare(frameHistory, now, exposureNanos);
            if (canRoll) direct = SpoutFrameSender.beginDirectFrame(w, h);
            boolean rolling = canRoll && RollingExposureRenderer.render(main, sumReadTarget, sumWriteTarget,
                    direct != null ? direct.target() : recordingTarget, frame.target, rollingExposure, now);
            if (canRoll && direct != null && !rolling) {
                SpoutFrameSender.disableDirectAfterRenderFailure();
                RollingExposureRenderer.invalidate();
            }
            if (rolling) {
                if (!RollingExposureRenderer.usesSplitState()) {
                    RenderTarget swap = sumReadTarget;
                    sumReadTarget = sumWriteTarget;
                    sumWriteTarget = swap;
                }
                rollingExposure.updated(now);
            } else {
                copyTexture(main, frame.target);
                applyCursorOverlay(frame.target, mc, w, h);
                boolean rebase = !cursorVisible && RollingExposureRenderer.available();
                if (composeFrame(mc, frame.target, now, exposureNanos, rebase) && rebase) {
                    rollingExposure.rebased(now, exposureNanos);
                    RollingExposureRenderer.rebased();
                } else {
                    rollingExposure.reset();
                }
            }
            long beforeSend = timingStart == 0L ? 0L : System.nanoTime();
            if (timingStart != 0L) CaptureGpuTimings.beforeSend();
            if (direct != null) direct.finish(rolling);
            if (direct == null || !rolling) SpoutFrameSender.send(recordingTarget, w, h);
            if (timingStart != 0L) CaptureGpuTimings.end();
            CaptureTimings.record(timingStart, beforeSend, rolling);
        } finally {
            // A retired source may be reused only after the subtraction draw has been recorded.
            try { if (direct != null) direct.close(); }
            finally {
                frameHistory.releaseRetired();
                savedAllocator = null;
            }
        }
    }

    private static void handleCaptureFailure(Throwable t) {
        savedAllocator = null;
        retryCaptureAfterNanos = System.nanoTime() + 1_000_000_000L;

        try {
            SpoutFrameSender.discardAfterFailure();
        } catch (Throwable ignored) {
        }
        discardRecordingStateWithoutRenderCleanup();

        if (!warnedCaptureFailure) {
            warnedCaptureFailure = true;
            System.err.println("[FBO] Spout capture was reset after a rendering/resource reload error: " + compactError(t));
        }
    }

    private static String compactError(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    private static void captureMenuFrameAndPresent(Minecraft mc, RenderTarget main, int w, int h) {
        ensureTargets(w, h);
        frameHistory.reset();
        rollingExposure.reset();
        try {
            savedAllocator = GraphicsResourceAllocator.UNPOOLED;
            copyTexture(main, recordingTarget);
            applyCursorOverlay(recordingTarget, mc, w, h);
            SpoutFrameSender.send(recordingTarget, w, h);
        } finally {
            savedAllocator = null;
        }
    }

    private static boolean composeFrame(Minecraft mc, RenderTarget newest, long now, long exposureNanos, boolean forceLinear) {
        int limit = activeFrameBlendBatchLimit();
        long start = now - exposureNanos;
        long totalWeight = 0L;
        int totalSamples = 0;
        for (ExposureFrameHistory.Frame<RenderTarget> frame : frameHistory.frames()) {
            long overlap = frame.overlap(start, now);
            if (overlap > 0L) {
                totalWeight += overlap;
                totalSamples++;
            }
        }
        if ((totalSamples <= 1 && !forceLinear) || totalWeight <= 0L) {
            copyTexture(newest, recordingTarget);
            return false;
        }

        // Most exposures fit in one pass: blend directly into the 8-bit Spout target,
        // using the original color conversion, with no float intermediate or resolve pass.
        if (totalSamples <= limit && !forceLinear) {
            recDirectChain = loadChain(mc, recDirectChain,
                    limit <= GL_BATCH_LIMIT ? "frame_blending_direct_gl" : "frame_blending_direct");
            if (recDirectChain == null) { copyTexture(newest, recordingTarget); return false; }
            int count = 0;
            for (ExposureFrameHistory.Frame<RenderTarget> frame : frameHistory.frames()) {
                long overlap = frame.overlap(start, now);
                if (overlap > 0L) setBatchSample(count++, frame.target, (float)((double)overlap / totalWeight));
            }
            PostPass pass = firstPass(recDirectChain);
            if (pass == null) { copyTexture(newest, recordingTarget); return false; }
            bindBatchInputs(pass, newest, count, limit, false);
            processToTarget(recDirectChain, newest, recordingTarget);
            return false;
        }

        recCombineChain = loadChain(mc, recCombineChain, limit <= GL_BATCH_LIMIT ? "frame_blending_gl" : "frame_blending");
        resolveChain = loadChain(mc, resolveChain, "frame_resolve");
        if (recCombineChain == null || resolveChain == null) {
            copyTexture(newest, recordingTarget);
            return false;
        }
        if (sumReadTarget == null) {
            // Float intermediates avoid quantization when a full exposure needs multiple batches.
            sumReadTarget = colorTarget("Linear exposure A", lastW, lastH, RecordingPipelineFormats.BLEND_FORMAT);
            sumWriteTarget = colorTarget("Linear exposure B", lastW, lastH, RecordingPipelineFormats.BLEND_FORMAT);
        }

        int batchCount = 0;
        boolean hasPrevious = false;
        for (ExposureFrameHistory.Frame<RenderTarget> frame : frameHistory.frames()) {
            long overlap = frame.overlap(start, now);
            if (overlap <= 0L) continue;
            setBatchSample(batchCount, frame.target, (float)((double)overlap / totalWeight));
            if (++batchCount == limit) {
                blendBatch(newest, batchCount, limit, hasPrevious);
                hasPrevious = true;
                batchCount = 0;
            }
        }
        if (batchCount > 0) blendBatch(newest, batchCount, limit, hasPrevious);
        processToTarget(resolveChain, sumReadTarget, recordingTarget);
        return true;
    }

    private static void blendBatch(RenderTarget fallback, int sampleCount, int limit, boolean hasPrevious) {
        PostPass pass = firstPass(recCombineChain);
        if (pass == null) throw new IllegalStateException("Missing recording blend pass");
        bindBatchInputs(pass, fallback, sampleCount, limit, hasPrevious);
        if (previousSumInput == null) previousSumInput = new MutableTextureInput("PreviousSum", sumReadTarget);
        else previousSumInput.setTarget(sumReadTarget);
        swapSampler(pass, "PreviousSum", previousSumInput);

        // Every batch has a distinct destination; no texture is sampled while being written.
        processToTarget(recCombineChain, fallback, sumWriteTarget);
        RenderTarget swap = sumReadTarget;
        sumReadTarget = sumWriteTarget;
        sumWriteTarget = swap;
    }

    private static void setBatchSample(int index, RenderTarget target, float weight) {
        batchWeights[index] = weight;
        if (recHistoryInputs[index] == null) {
            recHistoryInputs[index] = new MutableTextureInput(SAMPLE_NAMES[index], target);
        } else {
            recHistoryInputs[index].setTarget(target);
        }
    }

    private static void bindBatchInputs(PostPass pass, RenderTarget fallback,
                                        int sampleCount, int limit, boolean hasPrevious) {
        Map<String, GpuBuffer> uniforms = ((PostPassAccessor)pass).getCustomUniforms();
        GpuBuffer ubo = nextRecCombineUBO();
        uniforms.put(FRAME_BLEND_UBO, ubo);
        writeBlendParamsUBO(ubo, sampleCount, hasPrevious);

        for (int i = 0; i < limit; i++) {
            if (i >= sampleCount) {
                if (recHistoryInputs[i] == null) recHistoryInputs[i] = new MutableTextureInput(SAMPLE_NAMES[i], fallback);
                else recHistoryInputs[i].setTarget(fallback);
            }
            swapSampler(pass, SAMPLE_NAMES[i], recHistoryInputs[i]);
        }
    }

    private static void processToTarget(PostChain chain, RenderTarget source, RenderTarget output) {
        FrameGraphBuilder graph = new FrameGraphBuilder();
        Map<Identifier, ResourceHandle<RenderTarget>> targets = new HashMap<>();
        targets.put(PostChain.MAIN_TARGET_ID, graph.importExternal("recording_source", source));
        targets.put(OUTPUT_TARGET_ID, graph.importExternal("recording_output", output));
        PostChain.TargetBundle bundle = new PostChain.TargetBundle() {
            @Override
            public void replace(@NonNull Identifier id, @NonNull ResourceHandle<RenderTarget> handle) {
                if (!targets.containsKey(id)) throw new IllegalArgumentException("Unknown recording target " + id);
                targets.put(id, handle);
            }

            @Override
            public ResourceHandle<RenderTarget> get(@NonNull Identifier id) { return targets.get(id); }
        };
        chain.addToFrame(graph, output.width, output.height, bundle);
        graph.execute(savedAllocator);
    }

    private static void applyCursorOverlay(RenderTarget target, Minecraft mc, int w, int h) {
        CursorState cursor = getCursorState(mc, w, h);
        if (!cursor.visible) {
            recPrevRawCursorVisible = false;
            return;
        }

        float prevDrawX = cursor.x;
        float prevDrawY = cursor.y;
        if (recPrevRawCursorVisible) {
            prevDrawX = recPrevRawCursorX;
            prevDrawY = recPrevRawCursorY;
        }

        recCursorChain = loadChain(mc, recCursorChain, "cursor_overlay");
        if (recCursorChain == null) return;
        PostPass cursorPass = firstPass(recCursorChain);
        if (cursorPass == null) return;

        Map<String, GpuBuffer> cursorUniforms = ((PostPassAccessor) cursorPass).getCustomUniforms();
        if (!cursorUniforms.containsKey("CursorOverlayUniforms")) return;

        if (recCursorUBO == null) recCursorUBO = GpuBufferUtil.createUBO("CursorOverlayUniforms", CURSOR_UBO_SIZE);
        GpuBuffer savedCursorUBO = cursorUniforms.put("CursorOverlayUniforms", recCursorUBO);
        PostPass.Input savedCursorSampler = null;

        try {
            writeCursorUBO(recCursorUBO, cursor.x, cursor.y, cursor.scale, prevDrawX, prevDrawY);

            if (recCursorTextureInput == null) {
                recCursorTextureInput = new ResourceTextureInput("Cursor", CURSOR_TEXTURE_ID);
            }

            GpuTextureView testView = getTextureView(mc, CURSOR_TEXTURE_ID);
            if (testView == null) return;

            savedCursorSampler = swapSampler(cursorPass, "Cursor", recCursorTextureInput);
            recCursorChain.process(target, savedAllocator);

            recPrevRawCursorX = cursor.x;
            recPrevRawCursorY = cursor.y;
            recPrevRawCursorVisible = true;
        } finally {
            if (savedCursorUBO != null) {
                cursorUniforms.put("CursorOverlayUniforms", savedCursorUBO);
            } else {
                cursorUniforms.remove("CursorOverlayUniforms");
            }
            if (savedCursorSampler != null) swapSampler(cursorPass, "Cursor", savedCursorSampler);
        }
    }

    private static void writeCursorUBO(GpuBuffer ubo,
                                       float cursorX, float cursorY, float cursorScale,
                                       float prevCursorX, float prevCursorY) {
        GpuBufferUtil.writeStd140(ubo, CURSOR_UBO_SIZE, b -> {
            b.putFloat(cursorX);
            b.putFloat(cursorY);
            b.putFloat(cursorScale);
            b.putFloat(1.0f);
            b.putFloat(prevCursorX);
            b.putFloat(prevCursorY);
            b.putFloat(1.0f);
            b.putFloat(0.0f);
        });
    }

    private static CursorState getCursorState(Minecraft mc, int renderWidth, int renderHeight) {
        try {
            if (mc.mouseHandler.isMouseGrabbed()) return CursorState.HIDDEN;
            long window = getGlfwWindowHandle(mc);
            if (window == 0) return CursorState.HIDDEN;

            int cursorMode = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
            if (cursorMode != GLFW.GLFW_CURSOR_NORMAL) return CursorState.HIDDEN;

            double[] xArr = new double[1];
            double[] yArr = new double[1];
            GLFW.glfwGetCursorPos(window, xArr, yArr);

            double winW = mc.getWindow().getWidth();
            double winH = mc.getWindow().getHeight();
            if (winW <= 0 || winH <= 0) return CursorState.HIDDEN;

            float x = (float) (xArr[0] * renderWidth / winW);
            float y = (float) (yArr[0] * renderHeight / winH);
            if (x < -32 || y < -32 || x > renderWidth + 32 || y > renderHeight + 32)
                return CursorState.HIDDEN;

            return new CursorState(x, y, 1.0f, true);
        } catch (Throwable ignored) {
            return CursorState.HIDDEN;
        }
    }

    private static long getGlfwWindowHandle(Minecraft mc) {
        if (glfwHandleResolved) return cachedGlfwHandle;
        try {
            Object windowObj = mc.getWindow();
            Class<?> type = windowObj.getClass();
            while (type != null) {
                for (Field f : type.getDeclaredFields()) {
                    if (f.getType() == long.class) {
                        f.setAccessible(true);
                        long val = f.getLong(windowObj);
                        if (val > 0) {
                            try {
                                GLFW.glfwGetInputMode(val, GLFW.GLFW_CURSOR);
                                cachedGlfwHandle = val;
                                glfwHandleResolved = true;
                                return cachedGlfwHandle;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        glfwHandleResolved = true;
        return 0;
    }

    private record CursorState(float x, float y, float scale, boolean visible) {
        private static final CursorState HIDDEN = new CursorState(0.0f, 0.0f, 1.0f, false);
    }

    public static void invalidateFrameBlending() {
        frameHistory.clear(RecordingShaderManager::destroyTargetBuffers);
        rollingExposure.reset();
        RollingExposureRenderer.invalidate();
        Arrays.fill(recHistoryInputs, null);
        previousSumInput = null;
        if (sumReadTarget != null) { destroyTargetBuffers(sumReadTarget); sumReadTarget = null; }
        if (sumWriteTarget != null) { destroyTargetBuffers(sumWriteTarget); sumWriteTarget = null; }
        closeRecCombineUBORing();
        GpuBufferUtil.closeQuietly(recCursorUBO);
        recCursorUBO = null;
        recCombineChain = null;
        recDirectChain = null;
        resolveChain = null;
        recCursorChain = null;
        recCursorTextureInput = null;
        recPrevRawCursorVisible = false;
    }

    public static void beginResourceReload() {
        resourceReloadActive = true;
        pauseOutput();
        pauseCaptureUntilNanos = Long.MAX_VALUE;
        SpoutFrameSender.beginResourceReload();
    }

    public static void endResourceReload() {
        resourceReloadActive = false;
        pauseOutput();
        pauseCaptureUntilNanos = System.nanoTime() + 1_000_000_000L;
        SpoutFrameSender.endResourceReload();
    }

    public static void pauseOutput() {
        CaptureTimings.reset();
        savedAllocator = null;
        retryCaptureAfterNanos = 0L;
        frameHistory.reset();
        rollingExposure.reset();
        recPrevRawCursorVisible = false;
    }

    @SuppressWarnings("unused")
    public static void destroyOnRenderThread() {
        if (isRenderThreadSafe()) {
            destroy();
            return;
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(RecordingShaderManager::destroy);
            return;
        } catch (Throwable ignored) {
        }

        destroyFromAnyThread();
    }

    public static void destroyFromAnyThread() {
        if (isRenderThreadSafe()) {
            destroy();
            return;
        }

        try {
            SpoutFrameSender.shutdownWithoutRenderThreadCleanup();
        } catch (Throwable ignored) {
        }
        discardRecordingStateWithoutRenderCleanup();
    }

    public static void destroy() {
        if (!isRenderThreadSafe()) {
            destroyFromAnyThread();
            return;
        }

        SpoutFrameSender.shutdown();
        CaptureTimings.reset();
        savedAllocator = null;
        retryCaptureAfterNanos = 0L;
        pauseCaptureUntilNanos = 0L;
        resourceReloadActive = false;
        warnedCaptureFailure = false;
        loggedCapturePhase = false;
        cachedGlfwHandle = 0;
        glfwHandleResolved = false;

        if (recordingTarget != null) { destroyTargetBuffers(recordingTarget); recordingTarget = null; }
        invalidateFrameBlending();
        lastW = 0;
        lastH = 0;
    }

    private static boolean isRenderThreadSafe() {
        try {
            return RenderSystem.isOnRenderThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void discardRecordingStateWithoutRenderCleanup() {
        savedAllocator = null;
        recordingTarget = null;
        sumReadTarget = null;
        sumWriteTarget = null;
        cachedGlfwHandle = 0;
        glfwHandleResolved = false;
        frameHistory.clear(ignored -> {});
        rollingExposure.reset();
        Arrays.fill(recHistoryInputs, null);
        previousSumInput = null;
        Arrays.fill(recCombineUBORing, null);
        recCursorUBO = null;
        recCombineChain = null;
        recDirectChain = null;
        resolveChain = null;
        recCursorChain = null;
        recCursorTextureInput = null;
        recPrevRawCursorVisible = false;
        lastW = 0;
        lastH = 0;
    }

    private static void ensureTargets(int w, int h) {
        if (recordingTarget != null && lastW == w && lastH == h) return;
        if (recordingTarget != null) destroyTargetBuffers(recordingTarget);
        invalidateFrameBlending();
        recordingTarget = colorTarget("OBS output", w, h, GpuFormat.RGBA8_UNORM);
        lastW = w;
        lastH = h;
    }

    private static RenderTarget newSourceFrameTarget() {
        return colorTarget("Source frame", lastW, lastH, GpuFormat.RGBA8_UNORM);
    }

    private static RenderTarget colorTarget(String name, int w, int h, GpuFormat format) {
        return new TextureTarget("Frameblend Output / " + name, w, h, false, format);
    }

    private static void destroyTargetBuffers(RenderTarget target) {
        try {
            target.destroyBuffers();
        } catch (Throwable ignored) {
        }
    }

    private static void copyTexture(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null) return;
        assert src.getColorTexture() != null;
        assert dst.getColorTexture() != null;
        int copyW = Math.min(src.width, dst.width);
        int copyH = Math.min(src.height, dst.height);
        if (copyW <= 0 || copyH <= 0) return;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                src.getColorTexture(), dst.getColorTexture(),
                0, 0, 0, 0, 0, copyW, copyH);
    }

    private static PostPass.Input swapSampler(PostPass pass, String samplerName, PostPass.Input replacement) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            if (samplerName.equals(inputs.get(i).samplerName())) {
                PostPass.Input old = inputs.get(i);
                inputs.set(i, replacement);
                return old;
            }
        }
        return null;
    }

    private static int activeFrameBlendBatchLimit() {
        return isOpenGlBackend() ? GL_BATCH_LIMIT : MAX_BATCH_SAMPLES;
    }

    private static boolean isOpenGlBackend() {
        try {
            Object device = RenderSystem.getDevice();
            String name = device.getClass().getName().toLowerCase(Locale.ROOT);
            Object backend = findBackend(device);
            if (backend != null) name += " " + backend.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("vulkan")) return false;
            return name.contains("opengl") || name.contains("gl");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object findBackend(Object device) {
        for (Class<?> c = device.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("backend");
                field.setAccessible(true);
                return field.get(device);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static GpuBuffer nextRecCombineUBO() {
        GpuBuffer ubo = recCombineUBORing[recCombineUBOIndex];
        if (ubo == null) {
            ubo = GpuBufferUtil.createUBO("RecFrameBlendParamsUniforms", FRAME_BLEND_UBO_SIZE);
            recCombineUBORing[recCombineUBOIndex] = ubo;
        }
        recCombineUBOIndex = (recCombineUBOIndex + 1) % UBO_RING_SIZE;
        return ubo;
    }

    private static void closeRecCombineUBORing() {
        for (int i = 0; i < recCombineUBORing.length; i++) {
            if (recCombineUBORing[i] != null) {
                GpuBufferUtil.closeQuietly(recCombineUBORing[i]);
                recCombineUBORing[i] = null;
            }
        }
        recCombineUBOIndex = 0;
    }

    private static PostChain loadChain(Minecraft mc, PostChain cached, String shaderPath) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) mc.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            PostChain result = cache.getOrLoadPostChain(
                    Identifier.fromNamespaceAndPath(FrameblendOutputMod.ID, shaderPath),
                    shaderPath.equals("cursor_overlay") ? LevelTargetBundle.MAIN_TARGETS : OUTPUT_TARGETS);
            if (result != cached) {
                switch (shaderPath) {
                    case "frame_blending", "frame_blending_gl", "frame_blending_direct", "frame_blending_direct_gl" -> closeRecCombineUBORing();
                    case "cursor_overlay" -> {
                        GpuBufferUtil.closeQuietly(recCursorUBO);
                        recCursorUBO = null;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            System.err.println("[Frameblend Output] Failed to load recording chain " + shaderPath + ": " + e.getMessage());
            return null;
        }
    }

    private static PostPass firstPass(PostChain chain) {
        List<PostPass> passes = ((PostChainAccessor) chain).getPasses();
        return passes.isEmpty() ? null : passes.getFirst();
    }

    private static void writeBlendParamsUBO(GpuBuffer ubo, int sampleCount, boolean hasPrevious) {
        GpuBufferUtil.writeStd140(ubo, FRAME_BLEND_UBO_SIZE, b -> {
            b.putFloat(1.0f);
            b.putInt(sampleCount);
            for (int i = 0; i < MAX_BATCH_SAMPLES; i++) {
                b.putFloat(i < sampleCount ? batchWeights[i] : 0.0f);
            }
            b.putInt(hasPrevious ? 1 : 0);
            b.putFloat(0.0f);
        });
    }

    private static class MutableTextureInput implements PostPass.Input {
        private final String samplerName;
        private RenderTarget target;

        MutableTextureInput(String samplerName, RenderTarget target) {
            this.samplerName = samplerName;
            this.target = target;
        }

        void setTarget(RenderTarget target) {
            this.target = target;
        }

        @Override
        public void addToPass(com.mojang.blaze3d.framegraph.@NonNull FramePass pass,
                              @NonNull Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {}

        @Override
        public @NonNull GpuTextureView texture(
                @NonNull Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {
            assert target.getColorTextureView() != null;
            return target.getColorTextureView();
        }

        @Override public @NonNull String samplerName() { return samplerName; }
        @Override public boolean bilinear() { return false; }
    }

    private static class ResourceTextureInput implements PostPass.Input {
        private final String samplerName;
        private final Identifier textureId;

        ResourceTextureInput(String samplerName, Identifier textureId) {
            this.samplerName = samplerName;
            this.textureId = textureId;
        }

        @Override
        public void addToPass(com.mojang.blaze3d.framegraph.@NonNull FramePass pass,
                              @NonNull Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {}

        @Override
        public @Nullable GpuTextureView texture(@NonNull Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {
            Minecraft mc = Minecraft.getInstance();
            return getTextureView(mc, textureId);
        }

        @Override public @NonNull String samplerName() { return samplerName; }
        @Override public boolean bilinear() { return true; }
    }

    private static GpuTextureView getTextureView(Minecraft mc, Identifier textureId) {
        try {
            return mc.getTextureManager().getTexture(textureId).getTextureView();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
