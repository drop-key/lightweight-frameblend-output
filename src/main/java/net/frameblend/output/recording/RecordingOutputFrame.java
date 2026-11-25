package net.frameblend.output.recording;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** A reserved GPU output image; uncommitted writes are drained without publication. */
interface RecordingOutputFrame extends AutoCloseable {
    RenderTarget target();
    void finish(boolean publish);
    @Override void close();
}
