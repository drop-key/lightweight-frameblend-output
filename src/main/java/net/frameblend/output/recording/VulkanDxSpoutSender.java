package net.frameblend.output.recording;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Three shared DirectX images, with nonblocking ownership checks on the game thread. */
final class VulkanDxSpoutSender {
    private static final int SIZE = 3;
    private static VulkanDevice device;
    private static long nativeContext, semaphore, sequence;
    private static int width, height, next;
    private static boolean disabled, paused, directDisabled;
    private static AsyncFramePublisher<Slot> publisher;
    private static final Slot[] slots = new Slot[SIZE];
    private static volatile long sent;
    private static long busy, direct, started;

    static final class Frame implements RecordingOutputFrame {
        private final Slot slot;
        private boolean finished;
        private Frame(Slot slot) { this.slot = slot; }
        @Override public RenderTarget target() { return slot.target; }
        @Override public void finish(boolean publish) {
            if (finished) return;
            finished = true;
            transfer(slot, false);
            slot.value = ++sequence;
            device.createCommandEncoder().signalSemaphore(semaphore, slot.value, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            slot.publish = publish;
            slot.queued = true;
            if (publish) direct++;
        }
        @Override public void close() { finish(false); }
    }

    static Frame begin(VulkanDevice backend, int w, int h) {
        return directDisabled || Boolean.getBoolean("frameblendoutput.copySpout") ? null : reserve(backend, w, h);
    }

    private static Frame reserve(VulkanDevice backend, int w, int h) {
        if (disabled || paused || backend == null || w <= 0 || h <= 0 || Boolean.getBoolean("frameblendoutput.glSpout")
                || "false".equalsIgnoreCase(System.getProperty("frameblendoutput.vulkanSpoutExternal"))
                || !System.getProperty("os.name", "").startsWith("Windows")) return null;
        try {
            ensure(backend, w, h);
            publisher.checkFailure();
            publishQueued();
            Slot slot = takeSlot();
            if (slot == null) { busy++; return null; }
            transfer(slot, true);
            slot.pending = true;
            return new Frame(slot);
        } catch (Throwable failure) {
            fail(failure);
            return null;
        }
    }

    static boolean send(VulkanDevice backend, GpuTexture source, int w, int h) {
        if (disabled || paused || Boolean.getBoolean("frameblendoutput.glSpout")) return false;
        try (var frame = reserve(backend, w, h)) {
            if (frame == null) return active();
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, frame.target().getColorTexture(), 0, 0, 0, 0, 0, w, h);
            frame.finish(true);
            direct--;
            return true;
        } catch (Throwable failure) { fail(failure); return false; }
    }

    static boolean active() { return nativeContext != 0 && !disabled; }
    static void disableDirect() { directDisabled = true; }

    static void onFrameSubmitted() {
        if (publisher == null || paused || disabled) return;
        try { publisher.checkFailure(); publishQueued(); }
        catch (Throwable failure) { fail(failure); }
    }

    private static Slot takeSlot() {
        long completed = DxSpoutBridge.nCompleted(nativeContext);
        for (int i = 0; i < SIZE; i++) {
            int index = (next + i) % SIZE;
            Slot slot = slots[index];
            if (slot.pending && !slot.queued && slot.value <= completed) slot.pending = false;
            if (!slot.pending) { next = (index + 1) % SIZE; return slot; }
        }
        return null;
    }

    private static void ensure(VulkanDevice backend, int w, int h) throws Exception {
        if (nativeContext != 0 && device == backend && width == w && height == h) { ensurePublisher(); return; }
        destroy();
        device = backend;
        width = w; height = h;
        try (var stack = MemoryStack.stackPush()) {
            verifySupport(stack);
            var id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address());
            VK12.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), props);
            if (!id.deviceLUIDValid()) throw new IllegalStateException("Vulkan adapter has no Windows LUID");
            DxSpoutBridge.load();
            nativeContext = DxSpoutBridge.nCreate(id.deviceLUID(), System.getProperty("frameblendoutput.spoutSenderName", "Frameblend Output"), w, h, SIZE);
            if (nativeContext == 0) throw new IllegalStateException("Could not create DirectX output");
            var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE);
            var create = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type.address());
            var handle = stack.mallocLong(1);
            check(VK12.vkCreateSemaphore(device.vkDevice(), create, null, handle), "Create DirectX semaphore");
            semaphore = handle.get(0);
            var imported = VkImportSemaphoreWin32HandleInfoKHR.calloc(stack).sType$Default().semaphore(semaphore)
                    .handleType(VK12.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT).handle(DxSpoutBridge.nReadyHandle(nativeContext));
            // LWJGL 3.4.1 incorrectly requires BOTH handle and name here,
            // although Vulkan requires one and forbids supplying both.
            // Invoke the same checked device function with the valid handle-only struct.
            long importFunction = device.vkDevice().getCapabilities().vkImportSemaphoreWin32HandleKHR;
            if (importFunction == 0) throw new IllegalStateException("External Win32 semaphores unavailable");
            check(org.lwjgl.system.JNI.callPPI(device.vkDevice().address(), imported.address(), importFunction), "Import DirectX ready fence");
            for (int i = 0; i < SIZE; i++) {
                slots[i] = new Slot(i);
                importTexture(slots[i], DxSpoutBridge.nTextureHandle(nativeContext, i));
            }
        }
        ensurePublisher();
        started = System.nanoTime();
        System.out.println("[FBO] Spout uses direct Vulkan/DirectX output; full-resolution FP32 blur is unchanged.");
    }

    private static void verifySupport(MemoryStack stack) {
        var capabilities = device.vkDevice().getCapabilities();
        if (!capabilities.VK_KHR_external_memory_win32 || !capabilities.VK_KHR_external_semaphore_win32)
            throw new IllegalStateException("Win32 external sharing extensions are not enabled");
        var external = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default()
                .handleType(VK12.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT);
        var request = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default().pNext(external.address())
                .format(VK12.VK_FORMAT_R8G8B8A8_UNORM).type(VK12.VK_IMAGE_TYPE_2D).tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                .usage(VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        var externalProperties = VkExternalImageFormatProperties.calloc(stack).sType$Default();
        var properties = VkImageFormatProperties2.calloc(stack).sType$Default().pNext(externalProperties.address());
        check(VK12.vkGetPhysicalDeviceImageFormatProperties2(device.vkDevice().getPhysicalDevice(), request, properties), "Query DirectX image sharing");
        if ((externalProperties.externalMemoryProperties().externalMemoryFeatures() & VK12.VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0)
            throw new IllegalStateException("DirectX textures cannot be imported on this Vulkan device");
        var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE);
        var semaphoreRequest = VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack).sType$Default().pNext(type.address())
                .handleType(VK12.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT);
        var semaphoreProperties = VkExternalSemaphoreProperties.calloc(stack).sType$Default();
        VK12.vkGetPhysicalDeviceExternalSemaphoreProperties(device.vkDevice().getPhysicalDevice(), semaphoreRequest, semaphoreProperties);
        if ((semaphoreProperties.externalSemaphoreFeatures() & VK12.VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) == 0)
            throw new IllegalStateException("Shared DirectX timeline fences cannot be imported on this device");
    }

    private static void ensurePublisher() {
        if (publisher != null) return;
        publisher = new AsyncFramePublisher<>(SIZE, () -> {}, slot -> {
            DxSpoutBridge.nPublish(nativeContext, slot.index, slot.value, slot.publish);
            if (slot.publish) sent++;
        }, () -> DxSpoutBridge.nDrain(nativeContext));
    }

    private static void importTexture(Slot slot, long textureHandle) {
        try (var stack = MemoryStack.stackPush()) {
            var external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(VK12.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT);
            var image = VkImageCreateInfo.calloc(stack).sType$Default().pNext(external.address()).imageType(VK12.VK_IMAGE_TYPE_2D)
                    .format(VK12.VK_FORMAT_R8G8B8A8_UNORM).mipLevels(1).arrayLayers(1).samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL).usage(VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            image.extent().set(width, height, 1);
            var result = stack.mallocLong(1);
            check(VK12.vkCreateImage(device.vkDevice(), image, null, result), "Create imported DirectX image");
            slot.image = result.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetImageMemoryRequirements(device.vkDevice(), slot.image, requirements);
            var properties = VkMemoryWin32HandlePropertiesKHR.calloc(stack).sType$Default();
            check(KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR(device.vkDevice(), VK12.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT, textureHandle, properties), "Query DirectX image memory");
            int bits = requirements.memoryTypeBits() & properties.memoryTypeBits();
            if (bits == 0) throw new IllegalStateException("No compatible DirectX image memory type");
            var memoryTypes = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceMemoryProperties(device.vkDevice().getPhysicalDevice(), memoryTypes);
            int index = Integer.numberOfTrailingZeros(bits);
            for (int i = 0; i < memoryTypes.memoryTypeCount(); i++) {
                if ((bits & (1 << i)) != 0 && (memoryTypes.memoryTypes(i).propertyFlags() & VK12.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) { index = i; break; }
            }
            var imported = VkImportMemoryWin32HandleInfoKHR.calloc(stack).sType$Default().handleType(VK12.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT).handle(textureHandle);
            var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(slot.image).pNext(imported.address());
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size())
                    .memoryTypeIndex(index).pNext(dedicated.address());
            check(VK12.vkAllocateMemory(device.vkDevice(), allocation, null, result), "Import DirectX image memory");
            slot.memory = result.get(0);
            check(VK12.vkBindImageMemory(device.vkDevice(), slot.image, slot.memory, 0), "Bind DirectX image memory");
            slot.target = new SharedVulkanRenderTarget(device, slot.image, width, height);
        }
    }

    private static void transfer(Slot slot, boolean acquire) {
        var encoder = device.createCommandEncoder();
        var command = encoder.allocateAndBeginTransientCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(slot.image)
                    .oldLayout(slot.initialized ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(acquire ? 0 : VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(acquire ? VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT : 0)
                    .srcQueueFamilyIndex(acquire ? VK12.VK_QUEUE_FAMILY_EXTERNAL : device.graphicsQueue().queueFamilyIndex())
                    .dstQueueFamilyIndex(acquire ? device.graphicsQueue().queueFamilyIndex() : VK12.VK_QUEUE_FAMILY_EXTERNAL);
            barrier.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK12.vkCmdPipelineBarrier(command, acquire ? VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    acquire ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK12.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, barrier);
        }
        check(VK12.vkEndCommandBuffer(command), "Record DirectX image ownership transfer");
        encoder.execute(command);
        slot.initialized = true;
    }

    private static void publishQueued() {
        for (int i = 0; i < SIZE; i++) {
            Slot slot = slots[(next + i) % SIZE];
            if (slot != null && slot.queued) {
                if (!publisher.offer(slot)) throw new IllegalStateException("DirectX publisher stopped");
                slot.queued = false;
            }
        }
    }

    static void pause() {
        paused = true;
        if (publisher == null) return;
        device.createCommandEncoder().submit();
        if (publisher.isAccepting()) publishQueued();
        publisher.close();
        publisher = null;
    }

    static void resume() { paused = false; }

    static boolean discardWithoutRenderThreadCleanup() {
        paused = true;
        if (publisher == null) return nativeContext == 0;
        // Retain resources still referenced by GPU work. The render thread
        // drains them before freeing memory, or Windows releases them on exit.
        publisher.requestStop();
        return false;
    }

    static void destroy() {
        pause();
        if (device != null) device.graphicsQueue().waitIdle();
        for (int i = 0; i < SIZE; i++) {
            Slot slot = slots[i];
            if (slot == null) continue;
            if (slot.target != null) slot.target.destroyBuffers();
            if (slot.image != 0) VK12.vkDestroyImage(device.vkDevice(), slot.image, null);
            if (slot.memory != 0) VK12.vkFreeMemory(device.vkDevice(), slot.memory, null);
            slots[i] = null;
        }
        if (semaphore != 0) VK12.vkDestroySemaphore(device.vkDevice(), semaphore, null);
        if (nativeContext != 0) DxSpoutBridge.nDestroy(nativeContext);
        nativeContext = semaphore = sequence = 0;
        device = null;
        next = 0; width = height = 0;
        paused = false;
        sent = busy = direct = 0;
    }

    static void reportTimings() {
        if (started == 0 || !active()) return;
        System.out.printf(java.util.Locale.ROOT, "[FBO] DirectX Spout: %.0f sends/sec, %d busy attempts, %d direct frames.%n", sent * 1e9 / (System.nanoTime() - started), busy, direct);
    }

    private static void fail(Throwable failure) {
        disabled = true;
        System.err.println("[FBO] DirectX Spout unavailable; using existing transport: " + failure);
        if (Boolean.getBoolean("fbo.verifyDirectX")) failure.printStackTrace();
        try { destroy(); } catch (Throwable cleanup) { System.err.println("[FBO] DirectX cleanup failed: " + cleanup); }
    }

    private static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) throw new IllegalStateException(operation + ": " + result);
    }

    private static final class Slot {
        final int index;
        long image, memory, value;
        RenderTarget target;
        boolean pending, queued, publish, initialized;
        Slot(int index) { this.index = index; }
    }
}
