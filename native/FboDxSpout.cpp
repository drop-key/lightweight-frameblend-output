// SPDX-License-Identifier: LGPL-3.0-only
#define NOMINMAX
#include <windows.h>
#include <d3d11_4.h>
#include <d3dcompiler.h>
#include <dxgi1_4.h>
#include <wrl/client.h>
#include <jni.h>
#include <memory>
#include <vector>
#include <stdexcept>
#include <exception>
#include <cstdio>
#include <cstring>
#include "SpoutDX.h"

using Microsoft::WRL::ComPtr;
static void check(HRESULT result, const char* operation) {
    if (FAILED(result)) {
        char message[256];
        std::snprintf(message, sizeof(message), "%s failed (HRESULT 0x%08lx)", operation, static_cast<unsigned long>(result));
        throw std::runtime_error(message);
    }
}
static void error(JNIEnv* env, const std::exception& failure) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), failure.what());
}
class FlippedSender : public spoutDX {
    ComPtr<ID3D11VertexShader> vertex;
    ComPtr<ID3D11PixelShader> pixel;
    ComPtr<ID3D11RenderTargetView> targetView;
    ID3D11Texture2D* viewedTexture = nullptr;
public:
    void initialize(int width, int height) {
        const char* vertexSource = "float4 main(uint id:SV_VertexID):SV_POSITION {float2 p=float2((id<<1)&2,id&2);return float4(p*float2(2,-2)+float2(-1,1),0,1);}";
        const char* pixelSource = "Texture2D<float4> frame:register(t0);float4 main(float4 p:SV_POSITION):SV_Target {uint w,h;frame.GetDimensions(w,h);return frame.Load(int3(int(p.x),int(h)-1-int(p.y),0));}";
        ComPtr<ID3DBlob> shader, errors;
        check(D3DCompile(vertexSource, std::strlen(vertexSource), nullptr, nullptr, nullptr, "main", "vs_5_0", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &shader, &errors), "Compile output vertex shader");
        check(m_pd3dDevice->CreateVertexShader(shader->GetBufferPointer(), shader->GetBufferSize(), nullptr, &vertex), "Create output vertex shader");
        shader.Reset(); errors.Reset();
        check(D3DCompile(pixelSource, std::strlen(pixelSource), nullptr, nullptr, nullptr, "main", "ps_5_0", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &shader, &errors), "Compile output pixel shader");
        check(m_pd3dDevice->CreatePixelShader(shader->GetBufferPointer(), shader->GetBufferSize(), nullptr, &pixel), "Create output pixel shader");
        D3D11_VIEWPORT viewport{0, 0, static_cast<float>(width), static_cast<float>(height), 0, 1};
        m_pImmediateContext->RSSetViewports(1, &viewport);
        m_pImmediateContext->IASetInputLayout(nullptr);
        m_pImmediateContext->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        m_pImmediateContext->VSSetShader(vertex.Get(), nullptr, 0);
        m_pImmediateContext->PSSetShader(pixel.Get(), nullptr, 0);
    }
    bool draw(ID3D11Texture2D* texture, ID3D11ShaderResourceView* sourceView) {
        D3D11_TEXTURE2D_DESC desc; texture->GetDesc(&desc);
        if (!CheckSender(desc.Width, desc.Height, static_cast<DWORD>(desc.Format))) throw std::runtime_error("Create Spout output failed");
        if (viewedTexture != m_pSharedTexture) {
            targetView.Reset();
            check(m_pd3dDevice->CreateRenderTargetView(m_pSharedTexture, nullptr, &targetView), "Create Spout output attachment");
            viewedTexture = m_pSharedTexture;
            ID3D11RenderTargetView* target = targetView.Get();
            m_pImmediateContext->OMSetRenderTargets(1, &target, nullptr);
        }
        if (!frame.CheckTextureAccess(m_pSharedTexture)) return false;
        m_pImmediateContext->PSSetShaderResources(0, 1, &sourceView);
        m_pImmediateContext->Draw(3, 0);
        return true;
    }
    void publish() {
        // The caller has flushed BOTH the output draw and its reuse fence.
        // Receivers must never see the frame count before GPU submission.
        frame.SetNewFrame();
        frame.AllowTextureAccess(m_pSharedTexture);
    }
};
struct DxContext {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext4> commands;
    ComPtr<ID3D11Fence> ready, done;
    std::vector<ComPtr<ID3D11Texture2D>> textures;
    std::vector<ComPtr<ID3D11ShaderResourceView>> sourceViews;
    std::vector<HANDLE> textureHandles;
    HANDLE readyHandle = nullptr;
    UINT64 lastSubmitted = 0;
    std::unique_ptr<FlippedSender> sender;
    ~DxContext() {
        if (sender) { sender->ReleaseSender(); sender.reset(); }
        for (HANDLE handle : textureHandles) if (handle) CloseHandle(handle);
        if (readyHandle) CloseHandle(readyHandle);
    }
    void drain() {
        if (lastSubmitted == 0 || done->GetCompletedValue() >= lastSubmitted) return;
        HANDLE event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!event) throw std::runtime_error("Could not create DirectX drain event");
        HRESULT result = done->SetEventOnCompletion(lastSubmitted, event);
        DWORD wait = SUCCEEDED(result) ? WaitForSingleObject(event, 5000) : WAIT_FAILED;
        CloseHandle(event);
        check(result, "SetEventOnCompletion");
        if (wait != WAIT_OBJECT_0) throw std::runtime_error("DirectX output did not finish within five seconds");
    }
};
static DxContext* context(jlong handle) { return reinterpret_cast<DxContext*>(handle); }
#define JNI(name) extern "C" JNIEXPORT

JNI(nCreate) jlong JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nCreate(JNIEnv* env, jclass, jobject luidBuffer, jstring senderName, jint width, jint height, jint count) {
    try {
        auto output = std::make_unique<DxContext>();
        LUID luid{};
        void* luidBytes = env->GetDirectBufferAddress(luidBuffer);
        if (!luidBytes || env->GetDirectBufferCapacity(luidBuffer) < sizeof(luid)) throw std::runtime_error("Missing Vulkan adapter LUID");
        std::memcpy(&luid, luidBytes, sizeof(luid));
        ComPtr<IDXGIFactory4> factory;
        check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "CreateDXGIFactory1");
        ComPtr<IDXGIAdapter1> adapter;
        check(factory->EnumAdapterByLuid(luid, IID_PPV_ARGS(&adapter)), "Match Vulkan DirectX adapter");
        ComPtr<ID3D11DeviceContext> immediate;
        check(D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                nullptr, 0, D3D11_SDK_VERSION, &output->device, nullptr, &immediate), "D3D11CreateDevice");
        check(immediate.As(&output->commands), "ID3D11DeviceContext4");
        ComPtr<ID3D11Device5> device5;
        check(output->device.As(&device5), "ID3D11Device5");
        check(device5->CreateFence(0, D3D11_FENCE_FLAG_SHARED, IID_PPV_ARGS(&output->ready)), "Create shared Vulkan-ready fence");
        check(device5->CreateFence(0, D3D11_FENCE_FLAG_NONE, IID_PPV_ARGS(&output->done)), "Create output completion fence");
        check(output->ready->CreateSharedHandle(nullptr, GENERIC_ALL, nullptr, &output->readyHandle), "Export ready fence");
        D3D11_TEXTURE2D_DESC desc{};
        desc.Width = width; desc.Height = height; desc.MipLevels = 1; desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED | D3D11_RESOURCE_MISC_SHARED_NTHANDLE;
        output->textures.resize(count); output->textureHandles.resize(count); output->sourceViews.resize(count);
        for (int i = 0; i < count; i++) {
            check(output->device->CreateTexture2D(&desc, nullptr, &output->textures[i]), "Create shared output texture");
            check(output->device->CreateShaderResourceView(output->textures[i].Get(), nullptr, &output->sourceViews[i]), "Create shared output source view");
            ComPtr<IDXGIResource1> resource;
            check(output->textures[i].As(&resource), "IDXGIResource1");
            check(resource->CreateSharedHandle(nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE, nullptr,
                    &output->textureHandles[i]), "Export output texture");
        }
        output->sender = std::make_unique<FlippedSender>();
        if (!output->sender->OpenDirectX11(output->device.Get())) throw std::runtime_error("Open Spout DirectX device failed");
        const char* name = env->GetStringUTFChars(senderName, nullptr);
        if (!name) return 0;
        bool named = output->sender->SetSenderName(name);
        env->ReleaseStringUTFChars(senderName, name);
        if (!named) throw std::runtime_error("Set Spout sender name failed");
        output->sender->SetSenderFormat(DXGI_FORMAT_R8G8B8A8_UNORM);
        output->sender->initialize(width, height);
        return reinterpret_cast<jlong>(output.release());
    } catch (const std::exception& failure) { error(env, failure); return 0; }
}
JNI(nTextureHandle) jlong JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nTextureHandle(JNIEnv*, jclass, jlong handle, jint slot) {
    return reinterpret_cast<jlong>(context(handle)->textureHandles.at(slot));
}
JNI(nReadyHandle) jlong JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nReadyHandle(JNIEnv*, jclass, jlong handle) {
    return reinterpret_cast<jlong>(context(handle)->readyHandle);
}
JNI(nPublish) void JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nPublish(JNIEnv* env, jclass, jlong handle, jint slot, jlong value, jboolean publish) {
    try {
        auto* output = context(handle);
        check(output->commands->Wait(output->ready.Get(), value), "Wait for Vulkan output");
        std::exception_ptr sendFailure;
        bool locked = false;
        try {
            if (publish) locked = output->sender->draw(output->textures.at(slot).Get(), output->sourceViews.at(slot).Get());
        } catch (...) { sendFailure = std::current_exception(); }
        check(output->commands->Signal(output->done.Get(), value), "Signal output completion");
        output->commands->Flush();
        output->lastSubmitted = value;
        if (locked) output->sender->publish();
        if (sendFailure) std::rethrow_exception(sendFailure);
    } catch (const std::exception& failure) { error(env, failure); }
}
JNI(nCompleted) jlong JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nCompleted(JNIEnv* env, jclass, jlong handle) {
    UINT64 value = context(handle)->done->GetCompletedValue();
    if (value == UINT64_MAX) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "DirectX output device was removed"); return 0; }
    return value;
}
JNI(nDrain) void JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nDrain(JNIEnv* env, jclass, jlong handle) {
    try { context(handle)->drain(); } catch (const std::exception& failure) { error(env, failure); }
}
JNI(nDestroy) void JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nDestroy(JNIEnv*, jclass, jlong handle) { delete context(handle); }

// Used only by the opt-in integration probe: an independent Spout receiver and
// D3D11 device verify the published texture, including row order and channel order.
JNI(nReadSender) jboolean JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nReadSender(JNIEnv* env, jclass, jstring senderName, jobject pixels, jint width, jint height) {
    try {
        spoutDX receiver;
        const char* name = env->GetStringUTFChars(senderName, nullptr);
        if (!name) return false;
        receiver.SetReceiverName(name);
        env->ReleaseStringUTFChars(senderName, name);
        if (!receiver.OpenDirectX11()) throw std::runtime_error("Open independent receiver failed");
        receiver.ReceiveTexture();
        receiver.ReceiveTexture();
        ID3D11Texture2D* texture = receiver.GetSenderTexture();
        if (!texture) throw std::runtime_error("Independent Spout receiver found no texture");
        D3D11_TEXTURE2D_DESC desc; texture->GetDesc(&desc);
        bool bgra = desc.Format == DXGI_FORMAT_B8G8R8A8_UNORM;
        if (desc.Width != width || desc.Height != height || (!bgra && desc.Format != DXGI_FORMAT_R8G8B8A8_UNORM)) throw std::runtime_error("Independent receiver format or size differs");
        desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = 0; desc.MiscFlags = 0; desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        ComPtr<ID3D11Texture2D> staging;
        check(receiver.GetDX11Device()->CreateTexture2D(&desc, nullptr, &staging), "Create receiver readback");
        auto* commands = receiver.GetDX11Context();
        commands->CopyResource(staging.Get(), texture);
        D3D11_MAPPED_SUBRESOURCE mapped{};
        check(commands->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped), "Read independent receiver");
        auto* bytes = static_cast<unsigned char*>(env->GetDirectBufferAddress(pixels));
        if (bytes && env->GetDirectBufferCapacity(pixels) >= static_cast<jlong>(width) * height * 4) {
            for (int y = 0; y < height; y++) {
                auto* row = static_cast<unsigned char*>(mapped.pData) + y * mapped.RowPitch;
                auto* destination = bytes + y * width * 4;
                std::memcpy(destination, row, width * 4);
                if (bgra) for (int x = 0; x < width; x++) std::swap(destination[x*4], destination[x*4+2]);
            }
        }
        commands->Unmap(staging.Get(), 0);
        return bytes != nullptr;
    } catch (const std::exception& failure) { error(env, failure); return false; }
}

JNI(nVerifyStreaming) jint JNICALL Java_net_frameblend_output_recording_DxSpoutBridge_nVerifyStreaming(JNIEnv* env, jclass, jstring senderName, jint width, jint height, jint frames) {
    try {
        spoutDX receiver;
        const char* name = env->GetStringUTFChars(senderName, nullptr);
        if (!name) return 0;
        receiver.SetReceiverName(name);
        env->ReleaseStringUTFChars(senderName, name);
        if (!receiver.OpenDirectX11()) throw std::runtime_error("Open streaming receiver failed");
        receiver.ReceiveTexture(); receiver.ReceiveTexture();
        auto* texture = receiver.GetSenderTexture();
        if (!texture) throw std::runtime_error("Streaming receiver found no output");
        D3D11_TEXTURE2D_DESC desc; texture->GetDesc(&desc);
        if (desc.Width != width || desc.Height != height) throw std::runtime_error("Streaming receiver size differs");
        desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = 0; desc.MiscFlags = 0; desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        ComPtr<ID3D11Texture2D> staging;
        check(receiver.GetDX11Device()->CreateTexture2D(&desc, nullptr, &staging), "Create streaming readback");
        auto* commands = receiver.GetDX11Context();
        for (int frame = 0; frame < frames; frame++) {
            receiver.ReceiveTexture();
            commands->CopyResource(staging.Get(), receiver.GetSenderTexture());
            D3D11_MAPPED_SUBRESOURCE mapped{};
            check(commands->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped), "Read streaming receiver");
            std::uint32_t expected; std::memcpy(&expected, mapped.pData, 4);
            bool consistent = true;
            for (int y = 0; y < height && consistent; y++) {
                auto* row = reinterpret_cast<const std::uint32_t*>(static_cast<const unsigned char*>(mapped.pData) + y * mapped.RowPitch);
                for (int x = 0; x < width; x++) if (row[x] != expected) { consistent = false; break; }
            }
            commands->Unmap(staging.Get(), 0);
            if (!consistent) throw std::runtime_error("A published frame tore during concurrent Spout reception");
        }
        return frames;
    } catch (const std::exception& failure) { error(env, failure); return 0; }
}
