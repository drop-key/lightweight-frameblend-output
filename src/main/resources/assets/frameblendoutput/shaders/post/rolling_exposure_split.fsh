#version 330

#ifdef SEED_FROM_RGBA
uniform sampler2D PreviousSampler;
#else
uniform sampler2D PreviousRGSampler;
uniform sampler2D PreviousBSampler;
#endif
uniform sampler2D CurrentSampler;
uniform sampler2D Removed0Sampler;
uniform sampler2D Removed1Sampler;
uniform sampler2D Removed2Sampler;
uniform sampler2D Removed3Sampler;

layout(std140) uniform RollingParams {
    vec4 removedWeights;
    float addedWeight;
    float padding0;
    float padding1;
    float padding2;
};

in vec2 texCoord;
layout(location = 0) out vec2 linearRG;
layout(location = 1) out float linearB;
layout(location = 2) out vec4 recordingColor;
layout(location = 3) out vec4 sourceColor;

vec3 linearSample(sampler2D image) {
    vec3 c = texture(image, texCoord).rgb;
    return c * c;
}

void main() {
    vec4 currentColor = texture(CurrentSampler, texCoord);
#ifdef SEED_FROM_RGBA
    vec3 average = texture(PreviousSampler, texCoord).rgb;
#else
    vec3 average = vec3(texture(PreviousRGSampler, texCoord).rg,
                        texture(PreviousBSampler, texCoord).r);
#endif
    vec3 change = currentColor.rgb * currentColor.rgb * addedWeight;
    if (removedWeights.x > 0.0) change -= linearSample(Removed0Sampler) * removedWeights.x;
    if (removedWeights.y > 0.0) change -= linearSample(Removed1Sampler) * removedWeights.y;
    if (removedWeights.z > 0.0) change -= linearSample(Removed2Sampler) * removedWeights.z;
    if (removedWeights.w > 0.0) change -= linearSample(Removed3Sampler) * removedWeights.w;
    average += change;
    // First use must also match attachment order for Minecraft's Vulkan linker.
    linearRG = average.rg;
    linearB = average.b;
    recordingColor = vec4(sqrt(max(average, vec3(0.0))), 1.0);
    sourceColor = currentColor;
}
