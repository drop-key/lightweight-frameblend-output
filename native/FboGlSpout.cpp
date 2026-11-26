// SPDX-License-Identifier: LGPL-3.0-only
#include <jni.h>
#include "third_party/Spout/SpoutLibrary/SpoutLibrary.h"
#include <vector>
#include <cstring>

// Spout's published COM-like interface is usable across C++ toolchains.
// Keep the SDK instance alive across output pauses; release its sender while
// the calling thread still owns the OpenGL context.
static SPOUTHANDLE sender = nullptr;
static bool initialize() {
    if (sender) return true;
    HMODULE library = GetModuleHandleW(L"SpoutLibrary.dll");
    if (!library) return false;
    auto factory = reinterpret_cast<SPOUTHANDLE (WINAPI*)()>(GetProcAddress(library, "GetSpout"));
    if (!factory) return false;
    sender = factory();
    return sender != nullptr;
}
extern "C" JNIEXPORT jint JNICALL Java_net_frameblend_output_recording_SpoutBridge_nBridgeVersion(JNIEnv*, jclass) { return 54; }
extern "C" JNIEXPORT jboolean JNICALL Java_net_frameblend_output_recording_SpoutBridge_nCreateSender(JNIEnv* env, jclass, jstring name, jint width, jint height) {
    if (!name || width <= 0 || height <= 0 || !initialize()) return false;
    const char* text = env->GetStringUTFChars(name, nullptr);
    if (!text) return false;
    sender->SetSenderName(text);
    env->ReleaseStringUTFChars(name, text);
    return true;
}
extern "C" JNIEXPORT jboolean JNICALL Java_net_frameblend_output_recording_SpoutBridge_nSendTexture(JNIEnv*, jclass, jint texture, jint target, jint width, jint height, jboolean invert) {
    return sender && width > 0 && height > 0 && sender->SendTexture(texture, target, width, height, invert, 0);
}
extern "C" JNIEXPORT jboolean JNICALL Java_net_frameblend_output_recording_SpoutBridge_nSendImage(JNIEnv* env, jclass, jobject pixels, jint width, jint height, jint pitch, jboolean invert) {
    if (!sender || width <= 0 || height <= 0 || width > INT_MAX / 4 || pitch < width * 4) return false;
    const auto* data = static_cast<const unsigned char*>(env->GetDirectBufferAddress(pixels));
    if (!data || env->GetDirectBufferCapacity(pixels) < static_cast<jlong>(pitch) * height) return false;
    if (pitch == width * 4) return sender->SendImage(data, width, height, GL_RGBA, invert);
    std::vector<unsigned char> packed(static_cast<size_t>(width) * height * 4);
    for (int row = 0; row < height; row++) std::memcpy(packed.data() + static_cast<size_t>(row) * width * 4, data + static_cast<size_t>(row) * pitch, width * 4);
    return sender->SendImage(packed.data(), width, height, GL_RGBA, invert);
}
extern "C" JNIEXPORT void JNICALL Java_net_frameblend_output_recording_SpoutBridge_nReleaseSender(JNIEnv*, jclass) {
    if (sender) sender->ReleaseSender(0);
}
