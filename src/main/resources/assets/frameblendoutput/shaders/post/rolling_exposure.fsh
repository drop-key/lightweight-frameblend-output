#version 330

uniform sampler2D PreviousSampler;
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
layout(location = 0) out vec4 linearAverage;
layout(location = 1) out vec4 recordingColor;
layout(location = 2) out vec4 sourceColor;

vec3 linearSample(sampler2D image) {
    vec3 c = texture(image, texCoord).rgb;
    return c * c;
}

void main() {
    vec4 currentColor = texture(CurrentSampler, texCoord);
    vec3 average = texture(PreviousSampler, texCoord).rgb;
    // Cancel entering/leaving contributions before changing the stored mean.
    // Constant colors then have an exact zero delta, avoiding biased drift from
    // rounding a large mean once for addition and again for subtraction.
    vec3 change = currentColor.rgb * currentColor.rgb * addedWeight;
    if (removedWeights.x > 0.0) change -= linearSample(Removed0Sampler) * removedWeights.x;
    if (removedWeights.y > 0.0) change -= linearSample(Removed1Sampler) * removedWeights.y;
    if (removedWeights.z > 0.0) change -= linearSample(Removed2Sampler) * removedWeights.z;
    if (removedWeights.w > 0.0) change -= linearSample(Removed3Sampler) * removedWeights.w;
    average += change;
    // Minecraft's Vulkan linker reassigns outputs in reflection order. First use
    // must match attachment order as well as the explicit OpenGL locations.
    linearAverage = vec4(average, 1.0);
    recordingColor = vec4(sqrt(max(average, vec3(0.0))), 1.0);
    sourceColor = currentColor;
}
