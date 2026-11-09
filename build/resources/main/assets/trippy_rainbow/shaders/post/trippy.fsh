#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform TrippyConfig {
    float Intensity;
};

layout(location = 0) out vec4 fragColor;

const float TAU = 6.28318530718;

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 grab(vec2 uv) {
    return texture(InSampler, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

void main() {
    float k = Intensity;

    // GameTime runs 0..1 over one 24000-tick day. Every animation below uses a whole
    // number of cycles per day, so it loops seamlessly when the day counter wraps.
    float t = GameTime;
    float phase = t * TAU;
    float hueClock = fract(t * 144.0);      // one full rainbow cycle roughly every 8 seconds

    float aspect = InSize.x / InSize.y;
    vec2 uv = texCoord;
    vec2 c = uv - 0.5;
    float r = length(c * vec2(aspect, 1.0));

    // 1) liquid wobble + ripples spreading out from the middle of the screen
    vec2 warp = vec2(
        sin(uv.y * 22.0 + phase * 240.0) + sin(uv.y * 9.0 - phase * 360.0 + uv.x * 4.0),
        cos(uv.x * 18.0 + phase * 360.0) + cos(uv.x * 7.0 - phase * 240.0 + uv.y * 5.0)) * 0.0022 * k;
    uv += warp;
    uv += c * sin(r * 30.0 - phase * 480.0) * 0.004 * k;

    // 2) chromatic aberration that slowly spins around
    vec2 off = vec2(cos(phase * 120.0), sin(phase * 120.0)) * 0.0035 * k;
    vec3 col = vec3(grab(uv + off).r, grab(uv).g, grab(uv - off).b);

    // 3) rainbow: rotate every pixel's hue by a moving, wavy gradient
    vec3 hsv = rgb2hsv(col);
    float spatial = uv.x * 0.9 + uv.y * 0.6 + 0.15 * sin(r * 10.0 - phase * 240.0);
    float h = fract(hsv.x * 0.35 + hueClock + spatial);
    float s = mix(hsv.y, clamp(hsv.y * 1.3 + 0.35, 0.0, 1.0), min(k, 1.0));
    float v = clamp(hsv.z * 1.1, 0.0, 1.0);
    vec3 rainbow = hsv2rgb(vec3(h, s, v));
    vec3 result = mix(col, rainbow, clamp(0.75 * k, 0.0, 0.97));

    // 4) pulsing rainbow glow ring and a rainbow rim around the screen edge
    float pulse = 0.5 + 0.5 * sin(phase * 360.0 - r * 12.0);
    result += hsv2rgb(vec3(fract(hueClock + r), 1.0, 1.0)) * 0.06 * k * pulse;
    float angle = atan(c.y, c.x) / TAU;
    float rim = smoothstep(0.35, 0.9, r);
    result += hsv2rgb(vec3(fract(angle + hueClock), 1.0, 1.0)) * rim * 0.25 * k;

    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
