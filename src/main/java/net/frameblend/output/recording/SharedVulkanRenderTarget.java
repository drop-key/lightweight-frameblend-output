package net.frameblend.output.recording;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

/** A Minecraft render attachment for an image owned by the shared-texture ring. */
final class SharedVulkanRenderTarget extends RenderTarget {
    SharedVulkanRenderTarget(VulkanDevice device, long image, int width, int height) {
        super("Frameblend Output / Shared output", false, GpuFormat.RGBA8_UNORM);
        this.width = width;
        this.height = height;
        colorTexture = new SharedTexture(device, image, width, height);
        try { colorTextureView = new SharedView(device, (SharedTexture)colorTexture); }
        catch (RuntimeException failure) { colorTexture.close(); throw failure; }
    }

    /** The owner must drain both APIs before deleting the external image. */
    @Override public void destroyBuffers() {
        if (colorTextureView != null) { colorTextureView.close(); colorTextureView = null; }
        if (colorTexture != null) { colorTexture.close(); colorTexture = null; }
    }

    private static final class SharedTexture extends VulkanGpuTexture {
        private final long sharedImage;
        private final int sharedWidth, sharedHeight;

        SharedTexture(VulkanDevice device, long image, int width, int height) {
            // Minecraft requires its concrete Vulkan texture type. Its base
            // constructor owns a tiny 1x1 compatibility allocation, which is
            // released normally; the external image remains owned by the ring.
            super(device, GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST
                    | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    "Frameblend Output / Shared image adapter", GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
            sharedImage = image;
            sharedWidth = width;
            sharedHeight = height;
        }

        @Override public long vkImage() { return sharedImage != 0 ? sharedImage : super.vkImage(); }
        @Override public int getWidth(int mip) { return sharedWidth != 0 ? Math.max(1, sharedWidth >> mip) : super.getWidth(mip); }
        @Override public int getHeight(int mip) { return sharedHeight != 0 ? Math.max(1, sharedHeight >> mip) : super.getHeight(mip); }
    }

    private static final class SharedView extends VulkanGpuTextureView {
        private boolean released;

        SharedView(VulkanDevice device, SharedTexture texture) { super(device, texture, 0, 1); }

        @Override public boolean isClosed() { return released; }

        @Override public void close() {
            if (released) return;
            // Shared-ring teardown has already waited for Vulkan and GL. The
            // view must be destroyed before its externally owned VkImage, so
            // it cannot use Minecraft's deferred image-view destruction queue.
            super.destroy();
            texture().removeViews();
            released = true;
        }
    }
}
