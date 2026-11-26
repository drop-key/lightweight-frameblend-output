package net.frameblend.output.recording;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;

import java.lang.reflect.Field;
import java.nio.LongBuffer;
import java.util.Arrays;

final class VulkanGlSharedSpoutSender {

    private static final int VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT = 0x00000002;
    private static final int VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT = 0x00000002;
    private static final int GL_HANDLE_TYPE_OPAQUE_WIN32_EXT = 0x9587;
    private static final int GL_LAYOUT_GENERAL_EXT = 0x958D;
    private static final int SHARED_RING_SIZE = 3;

    private static final boolean ASYNC_SEND = !Boolean.getBoolean("frameblendoutput.synchronousSpout");
    private static AsyncFramePublisher<SharedSlot> publisher;
    private static boolean disabled = false;
    private static boolean resourceReloadActive = false;
    private static boolean loggedSuccess = false;
    private static boolean loggedFailure = false;
    private static boolean loggedSemaphoreSync = false;

    private static VulkanDevice device = null;
    private static long glWindow = 0L;
    private static GLCapabilities glCapabilities = null;

    private static int width = 0;
    private static int height = 0;
    private static GpuFormat format = null;
    private static int nextQueueSlot = 0;
    private static int nextDrainSlot = 0;
    private static long queuedFrames, busyFrames, statisticsStart;
    private static long directFrames;
    private static boolean directDisabled, loggedDirect;
    private static volatile long sentFrames;

    private static final SharedSlot[] slots = new SharedSlot[SHARED_RING_SIZE];

    private VulkanGlSharedSpoutSender() {}

    static final class DirectFrame implements RecordingOutputFrame {
        private final SharedSlot slot;
        private boolean finished;

        private DirectFrame(SharedSlot slot) { this.slot = slot; }
        @Override public RenderTarget target() { return slot.renderTarget; }

        @Override public void finish(boolean publish) {
            if (finished) return;
            finished = true;
            // A semaphore makes the attachment writes available to the GL
            // reader. The image stays in GENERAL; no transfer copy is needed.
            try { device.createCommandEncoder().signalSemaphore(slot.vkSemaphore, 0L, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT); }
            catch (RuntimeException failure) { directDisabled = true; throw failure; }
            slot.shouldSend = publish;
            slot.readyToPublish = true;
            queuedFrames++;
            if (publish) directFrames++;
        }

        @Override public void close() { finish(false); }
    }

    static DirectFrame beginDirectFrame(int frameWidth, int frameHeight) {
        if (!ASYNC_SEND || directDisabled || disabled || resourceReloadActive || !externalSharingEnabled()
                || Boolean.getBoolean("frameblendoutput.copySpout") || frameWidth <= 0 || frameHeight <= 0) return null;
        if (!SpoutBridge.isAvailable()) return null;
        try {
            VulkanDevice vk = getVulkanDevice();
            if (vk == null) return null;
            ensureSharedTextures(vk, GpuFormat.RGBA8_UNORM, frameWidth, frameHeight);
            ensurePublisher();
            publisher.checkFailure();
            publishQueuedFrames();
            SharedSlot slot = takeFreeSlot();
            if (slot == null) return null;
            if (slot.renderTarget == null) slot.renderTarget = new SharedVulkanRenderTarget(vk, slot.vkImage, frameWidth, frameHeight);
            initializeForRendering(vk, slot);
            slot.pending = true;
            if (!loggedDirect) {
                loggedDirect = true;
                System.out.println("[FBO] Vulkan Spout renders directly into shared output textures (no intermediate image copy).");
            }
            return new DirectFrame(slot);
        } catch (Throwable e) {
            directDisabled = true;
            System.err.println("[FBO] Direct Spout rendering unavailable; retaining shared-copy output: " + compactError(e));
            return null;
        }
    }

    static void disableDirectAfterRenderFailure() {
        directDisabled = true;
        System.err.println("[FBO] Direct output render failed; retrying the ordinary recording target with shared-copy output.");
    }

    static void onFrameSubmitted() {
        if (publisher == null || resourceReloadActive || disabled) return;
        publisher.checkFailure();
        // This hook is strictly after Minecraft's Vulkan queue submission.
        // Earlier publication could enqueue a GL wait before its signal was
        // submitted; waiting a whole game frame adds unnecessary latency.
        publishQueuedFrames();
    }

    private static SharedSlot takeFreeSlot() {
        for (int i = 0; i < SHARED_RING_SIZE; i++) {
            int index = (nextQueueSlot + i) % SHARED_RING_SIZE;
            SharedSlot slot = slots[index];
            if (!slot.pending) {
                nextQueueSlot = (index + 1) % SHARED_RING_SIZE;
                return slot;
            }
        }
        return null;
    }

    private static void initializeForRendering(VulkanDevice vk, SharedSlot slot) {
        if (slot.imageInitialized) return;
        var encoder = vk.createCommandEncoder();
        var command = encoder.allocateAndBeginTransientCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(0).dstAccessMask(VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED).image(slot.vkImage);
            barrier.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK12.vkCmdPipelineBarrier(command, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, barrier);
        }
        if (VK12.vkEndCommandBuffer(command) != VK12.VK_SUCCESS) throw new IllegalStateException("Could not initialize shared render attachment");
        encoder.execute(command);
        slot.imageInitialized = true;
    }

    private static boolean externalSharingEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("frameblendoutput.vulkanSpoutExternal"));
    }

    static boolean send(GpuTexture source, int frameWidth, int frameHeight) {
        if (disabled || resourceReloadActive || !externalSharingEnabled()) return false;
        if (!(source instanceof VulkanGpuTexture vulkanSource)) return false;
        if (frameWidth <= 0 || frameHeight <= 0) return false;

        if (!SpoutBridge.isAvailable()) return false;

        try {
            VulkanDevice vulkanDevice = getVulkanDevice();
            if (vulkanDevice == null) return false;

            ensureSharedTextures(vulkanDevice, source.getFormat(), frameWidth, frameHeight);

            if (ASYNC_SEND) {
                ensurePublisher();
                publisher.checkFailure();
                publishQueuedFrames();
            } else {
                drainOneSharedTexture(frameWidth, frameHeight);
            }
            queueCurrentFrame(vulkanDevice, vulkanSource);

            if (!loggedSuccess) {
                loggedSuccess = true;
                System.out.println("[FBO] Vulkan Spout is using shared Vulkan/OpenGL texture output (" + (ASYNC_SEND ? "asynchronous publisher" : "synchronous sender") + ").");
            }
            return true;
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                System.err.println("[FBO] Vulkan shared Spout path failed; using readback fallback: " + compactError(t));
            }
            disabled = true;
            discardSharedTexturesAfterFailure();
            return false;
        }
    }

    private static String compactError(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    static VulkanDevice getVulkanDevice() {
        if (device != null) return device;

        Object gpuDevice = RenderSystem.getDevice();
        Object backend = findBackend(gpuDevice);
        if (backend instanceof VulkanDevice vulkanDevice) {
            device = vulkanDevice;
            return device;
        }
        return null;
    }

    private static Object findBackend(Object gpuDevice) {
        if (gpuDevice == null) return null;
        for (Class<?> c = gpuDevice.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("backend");
                field.setAccessible(true);
                return field.get(gpuDevice);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void ensureSharedTextures(VulkanDevice vulkanDevice, GpuFormat newFormat, int newWidth, int newHeight) {
        if (slots[0] != null && width == newWidth && height == newHeight && format == newFormat) return;

        destroySharedTextures();

        int glInternalFormat = toGlInternalFormat(newFormat);
        if (glInternalFormat == 0) {
            throw new IllegalStateException("Unsupported Spout Vulkan shared texture format: " + newFormat);
        }

        ensureOpenGlContext();
        for (int i = 0; i < SHARED_RING_SIZE; i++) {
            slots[i] = new SharedSlot();
            createExportableVulkanImage(vulkanDevice, slots[i], newFormat, newWidth, newHeight);
            slots[i].vkSemaphore = createExportableVulkanSemaphore(vulkanDevice);
            if (!ASYNC_SEND) slots[i].vkReturnSemaphore = createExportableVulkanSemaphore(vulkanDevice);
            importVulkanMemoryIntoOpenGl(slots[i], glInternalFormat, newWidth, newHeight);
            importVulkanSemaphoreIntoOpenGl(slots[i]);
        }

        width = newWidth;
        height = newHeight;
        format = newFormat;
        nextQueueSlot = 0;
        nextDrainSlot = 0;
        queuedFrames = busyFrames = sentFrames = 0L;
        directFrames = 0L;
        statisticsStart = System.nanoTime();
    }

    private static int toGlInternalFormat(GpuFormat gpuFormat) {
        if (gpuFormat == GpuFormat.RGBA8_UNORM || gpuFormat == GpuFormat.RGBA8_SNORM || gpuFormat == GpuFormat.RGBA8_UINT || gpuFormat == GpuFormat.RGBA8_SINT) {
            return GL11.GL_RGBA8;
        }
        if (gpuFormat == GpuFormat.RGB10A2_UNORM || gpuFormat == GpuFormat.RGB10A2_UINT) {
            return GL11.GL_RGB10_A2;
        }
        return 0;
    }

    private static void createExportableVulkanImage(VulkanDevice vulkanDevice, SharedSlot slot, GpuFormat gpuFormat, int imageWidth, int imageHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExternalMemoryImageCreateInfo externalInfo = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default();
            externalInfo.handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);

            VkImageCreateInfo imageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            imageCreateInfo.pNext(externalInfo.address());
            imageCreateInfo.imageType(VK12.VK_IMAGE_TYPE_2D);
            imageCreateInfo.extent().set(imageWidth, imageHeight, 1);
            imageCreateInfo.mipLevels(1);
            imageCreateInfo.arrayLayers(1);
            imageCreateInfo.format(VulkanConst.toVk(gpuFormat));
            imageCreateInfo.tiling(VK12.VK_IMAGE_TILING_OPTIMAL);
            imageCreateInfo.initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imageCreateInfo.usage(VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                    | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
            imageCreateInfo.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            imageCreateInfo.samples(VK12.VK_SAMPLE_COUNT_1_BIT);

            LongBuffer imagePtr = stack.callocLong(1);
            int imageResult = VK12.vkCreateImage(vulkanDevice.vkDevice(), imageCreateInfo, null, imagePtr);
            if (imageResult < 0) {
                throw new IllegalStateException("vkCreateImage failed: " + imageResult);
            }
            slot.vkImage = imagePtr.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetImageMemoryRequirements(vulkanDevice.vkDevice(), slot.vkImage, requirements);
            slot.allocationSize = requirements.size();

            int memoryTypeIndex = findMemoryType(vulkanDevice, requirements.memoryTypeBits());

            VkMemoryDedicatedAllocateInfo dedicatedInfo = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default();
            dedicatedInfo.image(slot.vkImage);

            VkExportMemoryAllocateInfo exportInfo = VkExportMemoryAllocateInfo.calloc(stack).sType$Default();
            exportInfo.handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            exportInfo.pNext(dedicatedInfo.address());

            VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default();
            allocateInfo.pNext(exportInfo.address());
            allocateInfo.allocationSize(slot.allocationSize);
            allocateInfo.memoryTypeIndex(memoryTypeIndex);

            LongBuffer memoryPtr = stack.callocLong(1);
            int memoryResult = VK12.vkAllocateMemory(vulkanDevice.vkDevice(), allocateInfo, null, memoryPtr);
            if (memoryResult < 0) {
                throw new IllegalStateException("vkAllocateMemory failed: " + memoryResult);
            }
            slot.vkMemory = memoryPtr.get(0);

            int bindResult = VK12.vkBindImageMemory(vulkanDevice.vkDevice(), slot.vkImage, slot.vkMemory, 0L);
            if (bindResult < 0) {
                throw new IllegalStateException("vkBindImageMemory failed: " + bindResult);
            }
        }
    }

    private static long createExportableVulkanSemaphore(VulkanDevice vulkanDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExportSemaphoreCreateInfo exportInfo = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default();
            exportInfo.handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);

            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            createInfo.pNext(exportInfo.address());

            LongBuffer semaphorePtr = stack.callocLong(1);
            int result = VK12.vkCreateSemaphore(vulkanDevice.vkDevice(), createInfo, null, semaphorePtr);
            if (result < 0) {
                throw new IllegalStateException("vkCreateSemaphore failed: " + result);
            }
            return semaphorePtr.get(0);
        }
    }

    private static int findMemoryType(VulkanDevice vulkanDevice, int typeBits) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceMemoryProperties(vulkanDevice.vkDevice().getPhysicalDevice(), properties);

            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) == 0) continue;
                if ((properties.memoryTypes(i).propertyFlags() & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) return i;
            }

            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0) return i;
            }
        }
        throw new IllegalStateException("No compatible Vulkan memory type for shared Spout texture");
    }

    private static void importVulkanMemoryIntoOpenGl(SharedSlot slot, int glInternalFormat, int imageWidth, int imageHeight) {
        long previousContext = GLFW.glfwGetCurrentContext();
        GLCapabilities previousCapabilities = currentCapabilitiesOrNull();
        boolean restorePreviousContext = previousContext != 0L && previousContext != glWindow;

        try {
            if (previousContext != glWindow) {
                GLFW.glfwMakeContextCurrent(glWindow);
                GL.setCapabilities(glCapabilities);
            }

            long handle = exportVulkanMemoryHandle(slot);
            slot.glMemoryObject = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(slot.glMemoryObject, slot.allocationSize, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);

            slot.glTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, slot.glTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, glInternalFormat, imageWidth, imageHeight, slot.glMemoryObject, 0L);
        } finally {
            if (restorePreviousContext) {
                GLFW.glfwMakeContextCurrent(previousContext);
                GL.setCapabilities(previousCapabilities);
            }
        }
    }

    private static void importVulkanSemaphoreIntoOpenGl(SharedSlot slot) {
        long previousContext = GLFW.glfwGetCurrentContext();
        GLCapabilities previousCapabilities = currentCapabilitiesOrNull();
        boolean restorePreviousContext = previousContext != 0L && previousContext != glWindow;

        try {
            if (previousContext != glWindow) {
                GLFW.glfwMakeContextCurrent(glWindow);
                GL.setCapabilities(glCapabilities);
            }

            long handle = exportVulkanSemaphoreHandle(slot.vkSemaphore);
            slot.glSemaphore = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(slot.glSemaphore, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
            if (!ASYNC_SEND) {
                slot.glReturnSemaphore = EXTSemaphore.glGenSemaphoresEXT();
                EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(slot.glReturnSemaphore,
                        GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, exportVulkanSemaphoreHandle(slot.vkReturnSemaphore));
            }
        } finally {
            if (restorePreviousContext) {
                GLFW.glfwMakeContextCurrent(previousContext);
                GL.setCapabilities(previousCapabilities);
            }
        }
    }

    private static long exportVulkanMemoryHandle(SharedSlot slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryGetWin32HandleInfoKHR handleInfo = VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default();
            handleInfo.memory(slot.vkMemory);
            handleInfo.handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);

            PointerBuffer handlePtr = stack.callocPointer(1);
            int result = KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device.vkDevice(), handleInfo, handlePtr);
            if (result < 0) {
                throw new IllegalStateException("vkGetMemoryWin32HandleKHR failed: " + result);
            }
            return handlePtr.get(0);
        }
    }

    private static long exportVulkanSemaphoreHandle(long semaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreGetWin32HandleInfoKHR handleInfo = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default();
            handleInfo.semaphore(semaphore);
            handleInfo.handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);

            PointerBuffer handlePtr = stack.callocPointer(1);
            int result = KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device.vkDevice(), handleInfo, handlePtr);
            if (result < 0) {
                throw new IllegalStateException("vkGetSemaphoreWin32HandleKHR failed: " + result);
            }
            return handlePtr.get(0);
        }
    }

    private static void queueCurrentFrame(VulkanDevice vulkanDevice, VulkanGpuTexture source) {
        SharedSlot slot = null;
        for (int i = 0; i < SHARED_RING_SIZE; i++) {
            int index = (nextQueueSlot + i) % SHARED_RING_SIZE;
            SharedSlot candidate = slots[index];
            if (!candidate.pending) {
                slot = candidate;
                nextQueueSlot = (index + 1) % SHARED_RING_SIZE;
                break;
            }
        }

        if (slot == null) { busyFrames++; return; }

        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        if (slot.glReturnPending) {
            // The GL worker has submitted its completion signal. Wait on the GPU,
            // never on the game thread, before reusing this slot for another copy.
            encoder.waitSemaphore(slot.vkReturnSemaphore, 0L, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);
            slot.glReturnPending = false;
        }
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!slot.imageInitialized) {
                VkImageMemoryBarrier.Buffer initBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
                initBarrier.oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
                initBarrier.newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                initBarrier.srcAccessMask(0);
                initBarrier.dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
                initBarrier.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
                initBarrier.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
                initBarrier.image(slot.vkImage);
                VkImageSubresourceRange range = initBarrier.subresourceRange();
                range.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT);
                range.baseMipLevel(0);
                range.levelCount(1);
                range.baseArrayLayer(0);
                range.layerCount(1);
                VK12.vkCmdPipelineBarrier(commandBuffer, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, initBarrier);
                slot.imageInitialized = true;
            }

            VkImageSubresourceLayers layers = VkImageSubresourceLayers.calloc(stack);
            layers.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT);
            layers.mipLevel(0);
            layers.baseArrayLayer(0);
            layers.layerCount(1);

            VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
            copy.srcSubresource(layers);
            copy.dstSubresource(layers);
            copy.srcOffset().set(0, 0, 0);
            copy.dstOffset().set(0, 0, 0);
            copy.extent().set(width, height, 1);
            VK12.vkCmdCopyImage(commandBuffer, source.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL, slot.vkImage, VK12.VK_IMAGE_LAYOUT_GENERAL, copy);

            VkImageMemoryBarrier.Buffer finishBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            finishBarrier.oldLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            finishBarrier.newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            finishBarrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            finishBarrier.dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT);
            finishBarrier.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            finishBarrier.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            finishBarrier.image(slot.vkImage);
            VkImageSubresourceRange finishRange = finishBarrier.subresourceRange();
            finishRange.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT);
            finishRange.baseMipLevel(0);
            finishRange.levelCount(1);
            finishRange.baseArrayLayer(0);
            finishRange.layerCount(1);
            VK12.vkCmdPipelineBarrier(commandBuffer, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, finishBarrier);
        }

        int endResult = VK12.vkEndCommandBuffer(commandBuffer);
        if (endResult < 0) {
            throw new IllegalStateException("vkEndCommandBuffer failed: " + endResult);
        }

        encoder.execute(commandBuffer);
        encoder.signalSemaphore(slot.vkSemaphore, 0L, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);

        slot.pending = true;
        slot.shouldSend = true;
        queuedFrames++;
        // Minecraft submits this frame after the capture hook. Hand it off on
        // the next frame, once its Vulkan signal has actually been submitted.
        slot.readyToPublish = ASYNC_SEND;
        if (!loggedSemaphoreSync) {
            loggedSemaphoreSync = true;
            System.out.println("[FBO] Vulkan Spout is using exported semaphore sync.");
        }
    }

    private static void drainOneSharedTexture(int frameWidth, int frameHeight) {
        for (int i = 0; i < SHARED_RING_SIZE; i++) {
            int index = (nextDrainSlot + i) % SHARED_RING_SIZE;
            SharedSlot slot = slots[index];
            if (slot == null || !slot.pending) continue;

            waitForVulkanCopyInOpenGl(slot);
            SpoutBridge.sendTexture(slot.glTexture, frameWidth, frameHeight);
            sentFrames++;
            signalGlCompletion(slot);
            slot.pending = false;
            nextDrainSlot = (index + 1) % SHARED_RING_SIZE;
            return;
        }
    }

    private static void drainPendingSharedTexturesForPause() {
        for (int i = 0; i < SHARED_RING_SIZE; i++) {
            SharedSlot slot = slots[i];
            if (slot == null || !slot.pending) continue;

            try {
                waitForVulkanCopyInOpenGl(slot);
                signalGlCompletion(slot);
            } catch (Throwable t) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    System.err.println("[FBO] Vulkan shared Spout pause failed; using readback fallback: " + compactError(t));
                }
                disabled = true;
            } finally {
                slot.pending = false;
            }
        }
        nextDrainSlot = nextQueueSlot;
    }

    private static void ensurePublisher() {
        if (publisher != null) return;
        final long publisherWindow = glWindow;
        final GLCapabilities publisherCapabilities = glCapabilities;
        final int publisherWidth = width, publisherHeight = height;
        // A context can be current on only one thread. Imports were initialized
        // on the render thread; all ongoing GL/Spout work now belongs to this worker.
        if (GLFW.glfwGetCurrentContext() == glWindow) {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
        }
        publisher = new AsyncFramePublisher<>(SHARED_RING_SIZE, () -> {
            GLFW.glfwMakeContextCurrent(publisherWindow);
            GL.setCapabilities(publisherCapabilities);
        }, slot -> {
            EXTSemaphore.glWaitSemaphoreEXT(slot.glSemaphore, new int[0],
                    new int[]{slot.glTexture}, new int[]{GL_LAYOUT_GENERAL_EXT});
            if (slot.shouldSend) {
                SpoutBridge.sendTexture(slot.glTexture, publisherWidth, publisherHeight);
                sentFrames++;
            }
            // A slot remains busy until its GPU reads are complete. Do not put
            // a return semaphore wait on Minecraft's graphics queue: that also
            // holds up unrelated rendering when the GL/DX sender runs behind.
            slot.glCompletion = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (slot.glCompletion == 0L) throw new IllegalStateException("Could not fence the shared Spout texture");
            GL11.glFlush();
        }, VulkanGlSharedSpoutSender::pollCompletedTextures, () -> {
            try {
                GL11.glFinish();
                pollCompletedTextures();
            }
            finally {
                GLFW.glfwMakeContextCurrent(0L);
                GL.setCapabilities(null);
            }
        });
    }

    private static boolean pollCompletedTextures() {
        boolean busy = false;
        for (SharedSlot slot : slots) {
            if (slot == null || slot.glCompletion == 0L) continue;
            int status = GL32.glClientWaitSync(slot.glCompletion, 0, 0L);
            if (status == GL32.GL_TIMEOUT_EXPIRED) { busy = true; continue; }
            if (status == GL32.GL_WAIT_FAILED) throw new IllegalStateException("Shared Spout texture completion check failed");
            GL32.glDeleteSync(slot.glCompletion);
            slot.glCompletion = 0L;
            // Release ownership only after the nonblocking GPU completion check.
            slot.pending = false;
        }
        return busy;
    }

    static void reportTimings() {
        long attempts = queuedFrames + busyFrames;
        if (statisticsStart == 0L || attempts == 0L) return;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[FBO] Spout transport: %.0f native send calls/sec, %.1f%% of publication attempts skipped while shared textures were busy, direct %d/%d frames (since current texture allocation; blur history retains every frame).",
                sentFrames * 1e9 / (System.nanoTime() - statisticsStart), busyFrames * 100.0 / attempts, directFrames, queuedFrames));
    }

    private static void signalGlCompletion(SharedSlot slot) {
        EXTSemaphore.glSignalSemaphoreEXT(slot.glReturnSemaphore, new int[0],
                new int[]{slot.glTexture}, new int[]{GL_LAYOUT_GENERAL_EXT});
        // Cross-API signals must be submitted before Vulkan can wait on them.
        GL11.glFlush();
        slot.glReturnPending = true;
    }

    private static void publishQueuedFrames() {
        // Walk in capture order, including across wraparound of the ring.
        for (int i = 0; i < SHARED_RING_SIZE; i++) {
            SharedSlot slot = slots[(nextQueueSlot + i) % SHARED_RING_SIZE];
            if (slot != null && slot.readyToPublish) {
                if (!publisher.offer(slot)) throw new IllegalStateException("Spout publisher stopped before receiving a queued frame");
                slot.readyToPublish = false;
            }
        }
    }

    private static void stopPublisher() {
        if (publisher == null) return;
        // A pending GL wait may refer to work recorded in the current Minecraft
        // frame. Submit it before joining during resize/reload, avoiding a cycle.
        if (device != null && RenderSystem.isOnRenderThread()) device.createCommandEncoder().submit();
        if (publisher.isAccepting()) publishQueuedFrames();
        publisher.close();
        publisher = null;
    }

    private static void waitForVulkanCopyInOpenGl(SharedSlot slot) {
        long previousContext = GLFW.glfwGetCurrentContext();
        GLCapabilities previousCapabilities = currentCapabilitiesOrNull();
        boolean restorePreviousContext = previousContext != 0L && previousContext != glWindow;

        try {
            if (previousContext != glWindow) {
                GLFW.glfwMakeContextCurrent(glWindow);
                GL.setCapabilities(glCapabilities);
            }

            EXTSemaphore.glWaitSemaphoreEXT(slot.glSemaphore, new int[0], new int[]{slot.glTexture}, new int[]{GL_LAYOUT_GENERAL_EXT});
        } finally {
            if (restorePreviousContext) {
                GLFW.glfwMakeContextCurrent(previousContext);
                GL.setCapabilities(previousCapabilities);
            }
        }
    }

    private static void ensureOpenGlContext() {
        if (glWindow != 0L) return;

        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("GLFW is not initialized");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

        glWindow = GLFW.glfwCreateWindow(1, 1, "Frameblend Output Spout", 0L, 0L);
        if (glWindow == 0L) {
            throw new IllegalStateException("Failed to create hidden OpenGL context for Vulkan Spout output");
        }

        GLFW.glfwMakeContextCurrent(glWindow);
        glCapabilities = GL.createCapabilities();
    }

    private static GLCapabilities currentCapabilitiesOrNull() {
        try {
            return GL.getCapabilities();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void destroy() {
        try {
            destroySharedTextures();

            long previousContext = GLFW.glfwGetCurrentContext();
            GLCapabilities previousCapabilities = currentCapabilitiesOrNull();
            if (glWindow != 0L) {
                long destroyedWindow = glWindow;
                try {
                    GLFW.glfwMakeContextCurrent(glWindow);
                    GL.setCapabilities(glCapabilities);
                } catch (Throwable ignored) {
                }
                try {
                    GLFW.glfwDestroyWindow(glWindow);
                } catch (Throwable ignored) {
                }
                glWindow = 0L;
                glCapabilities = null;
                if (previousContext != 0L && previousContext != destroyedWindow) {
                    GLFW.glfwMakeContextCurrent(previousContext);
                    GL.setCapabilities(previousCapabilities);
                } else {
                    GLFW.glfwMakeContextCurrent(0L);
                    GL.setCapabilities(null);
                }
            }
        } catch (Throwable ignored) {
        }

        discardWithoutRenderThreadCleanup();
    }

    static void discardAfterFailure() {
        discardSharedTexturesAfterFailure();
    }

    static void beginResourceReload() {
        resourceReloadActive = true;
        if (ASYNC_SEND) stopPublisher();
        else drainPendingSharedTexturesForPause();
    }

    static void endResourceReload() {
        resourceReloadActive = false;
    }

    static boolean discardWithoutRenderThreadCleanup() {
        // Off-thread shutdown cannot submit Minecraft's pending Vulkan work.
        // Keep its resources and bridge state alive until the worker has stopped;
        // a later render-thread destroy can safely submit, join, and free them.
        if (publisher != null) {
            publisher.requestStop();
            return false;
        }
        Arrays.fill(slots, null);
        device = null;
        glWindow = 0L;
        glCapabilities = null;
        width = 0;
        height = 0;
        format = null;
        nextQueueSlot = 0;
        nextDrainSlot = 0;
        disabled = false;
        resourceReloadActive = false;
        loggedSuccess = false;
        loggedFailure = false;
        loggedSemaphoreSync = false;
        directDisabled = loggedDirect = false;
        return true;
    }

    private static void discardSharedTexturesAfterFailure() {
        try { stopPublisher(); } catch (Throwable ignored) {
            if (publisher != null) publisher.requestStop();
            return;
        }
        Arrays.fill(slots, null);
        device = null;
        glWindow = 0L;
        glCapabilities = null;
        width = 0;
        height = 0;
        format = null;
        nextQueueSlot = 0;
        nextDrainSlot = 0;
    }

    private static void destroySharedTextures() {
        stopPublisher();
        if (device != null) device.graphicsQueue().waitIdle();
        for (SharedSlot slot : slots) {
            if (slot != null && slot.renderTarget != null) {
                slot.renderTarget.destroyBuffers();
                slot.renderTarget = null;
            }
        }
        long previousContext = GLFW.glfwGetCurrentContext();
        GLCapabilities previousCapabilities = currentCapabilitiesOrNull();
        boolean restorePreviousContext = previousContext != 0L && previousContext != glWindow;

        if (glWindow != 0L) {
            try {
                if (previousContext != glWindow) {
                    GLFW.glfwMakeContextCurrent(glWindow);
                    GL.setCapabilities(glCapabilities);
                }
                for (SharedSlot slot : slots) {
                    if (slot == null) continue;
                    if (slot.glTexture != 0) {
                        GL11.glDeleteTextures(slot.glTexture);
                        slot.glTexture = 0;
                    }
                    if (slot.glSemaphore != 0) {
                        EXTSemaphore.glDeleteSemaphoresEXT(slot.glSemaphore);
                        slot.glSemaphore = 0;
                    }
                    if (slot.glReturnSemaphore != 0) {
                        EXTSemaphore.glDeleteSemaphoresEXT(slot.glReturnSemaphore);
                        slot.glReturnSemaphore = 0;
                    }
                    if (slot.glMemoryObject != 0) {
                        EXTMemoryObject.glDeleteMemoryObjectsEXT(slot.glMemoryObject);
                        slot.glMemoryObject = 0;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (restorePreviousContext) {
                    GLFW.glfwMakeContextCurrent(previousContext);
                    GL.setCapabilities(previousCapabilities);
                }
            }
        }

        if (device != null) {
            try {
                device.graphicsQueue().waitIdle();
            } catch (Throwable ignored) {
            }
            for (SharedSlot slot : slots) {
                if (slot == null) continue;
                if (slot.vkSemaphore != 0L) {
                    try {
                        VK12.vkDestroySemaphore(device.vkDevice(), slot.vkSemaphore, null);
                    } catch (Throwable ignored) {
                    }
                    slot.vkSemaphore = 0L;
                }
                if (slot.vkReturnSemaphore != 0L) {
                    VK12.vkDestroySemaphore(device.vkDevice(), slot.vkReturnSemaphore, null);
                    slot.vkReturnSemaphore = 0L;
                }
                if (slot.vkImage != 0L) {
                    try {
                        VK12.vkDestroyImage(device.vkDevice(), slot.vkImage, null);
                    } catch (Throwable ignored) {
                    }
                    slot.vkImage = 0L;
                }
                if (slot.vkMemory != 0L) {
                    try {
                        VK12.vkFreeMemory(device.vkDevice(), slot.vkMemory, null);
                    } catch (Throwable ignored) {
                    }
                    slot.vkMemory = 0L;
                }
            }
        }

        Arrays.fill(slots, null);
        nextQueueSlot = 0;
        nextDrainSlot = 0;
        width = 0;
        height = 0;
        format = null;
    }

    private static final class SharedSlot {
        private long vkImage = 0L;
        private long vkMemory = 0L;
        private long allocationSize = 0L;
        private long vkSemaphore = 0L;
        private long vkReturnSemaphore = 0L;
        private int glMemoryObject = 0;
        private int glTexture = 0;
        private int glSemaphore = 0;
        private int glReturnSemaphore = 0;
        private long glCompletion = 0L;
        private boolean glReturnPending = false;
        private boolean readyToPublish = false;
        private boolean imageInitialized = false;
        private volatile boolean pending = false;
        private boolean shouldSend = true;
        private SharedVulkanRenderTarget renderTarget;
    }
}
