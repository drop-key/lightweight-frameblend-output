#version 330

uniform sampler2D InSampler;
in vec2 texCoord;
layout(location = 0) out vec4 color;

void main() {
    // Match the original frame-blend conversion, once after all linear samples.
    color = vec4(sqrt(max(texture(InSampler, texCoord).rgb, vec3(0.0))), 1.0);
}
