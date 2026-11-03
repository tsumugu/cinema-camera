precision highp float;
varying vec2 vUv;
uniform sampler2D uImage;
uniform sampler2D uGrain;
uniform vec2 uImageSize;
uniform float uStrength;
uniform float uCool;
uniform float uSaturation;
uniform float uContrast;
uniform float uGrainAmount;
uniform float uCompare;
uniform float uLinearInput;

// Initial SDR / sRGB look inspired by the selected reference, not a fitted
// reconstruction. These values are shared by preview and JPEG rendering.
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CONTRAST_POWER = 1.48;

// Fit RGB with one common chroma scale around luminance. Independent channel
// clipping changes hue (especially highlights); this keeps a single hue vector.
vec3 fitDisplayGamut(vec3 rgb) {
    float y=dot(rgb,LUMA);
    if(y<=0.0) return vec3(0.0);
    if(y>=1.0) return vec3(1.0);
    vec3 delta=rgb-vec3(y);
    vec3 positive=max(delta,vec3(0.000001));
    vec3 negative=max(-delta,vec3(0.000001));
    vec3 roomAbove=vec3(1.0-y)/positive;
    vec3 roomBelow=vec3(y)/negative;
    float scale=min(1.0,min(min(roomAbove.r,roomAbove.g),roomAbove.b));
    scale=min(scale,min(min(roomBelow.r,roomBelow.g),roomBelow.b));
    // This clamp only contains floating-point roundoff after the common scale.
    return clamp(vec3(y)+delta*max(scale,0.0),0.0,1.0);
}

float tone(float x) {
    float a = pow(max(x, 0.0), CONTRAST_POWER);
    float b = pow(max(1.0 - x, 0.0), CONTRAST_POWER);
    // The linear contribution preserves a nonzero shadow slope. Black remains
    // black; midtone separation increases without a hard clipping threshold.
    return mix(x, a / max(a + b, 0.000001), uContrast);
}

vec3 grade(vec3 rgb) {
    float y = dot(rgb, LUMA);
    vec3 c = mix(vec3(y), rgb, uSaturation);
    c *= tone(y) / max(y, 0.000001);

    // Revised cool look: visible in shadows and neutral midtones, tapering
    // toward black and bright highlights. Keep luminance/contrast unchanged.
    float cool = (1.0 - smoothstep(0.38, 0.90, y))
        * smoothstep(0.0, 0.10, y);
    vec3 tint = vec3(-0.012, 0.001, 0.035);
    tint -= vec3(dot(tint, LUMA));
    c += tint * cool * uCool;

    // G1: static fine luminance grain in normalized image coordinates. The
    // scale follows the image rather than the preview view's pixel count.
    vec2 aspect = uImageSize / min(uImageSize.x, uImageSize.y);
    vec2 p = vUv * aspect * (720.0 / 256.0);
    float noise = texture2D(uGrain, p).r
        + texture2D(uGrain, p + vec2(0.371, 0.629)).r - 1.0;
    float weight = smoothstep(0.0, 0.04, y)
        * (1.0 - smoothstep(0.92, 1.0, y));
    c += vec3(noise * uGrainAmount * weight);
    return fitDisplayGamut(c);
}

float encodeSrgb(float value) {
    return value <= 0.0031308 ? value * 12.92
        : 1.055 * pow(value, 1.0 / 2.4) - 0.055;
}

vec3 developBase(vec3 linearRgb) {
    float y=dot(linearRgb,LUMA);
    if(y<=0.000001) return vec3(0.0);
    // Tone-map linear luminance, then fit all three channels jointly before
    // sRGB encoding. No channel is clipped while another retains headroom.
    float mapped=y;
    if(y>0.65) mapped=0.65+0.35*(1.0-exp(-(y-0.65)/0.35));
    vec3 c=fitDisplayGamut(linearRgb*(mapped/y));
    return vec3(encodeSrgb(c.r),encodeSrgb(c.g),encodeSrgb(c.b));
}

void main() {
    vec4 source = texture2D(uImage, vUv);
    if (uLinearInput > 0.5) source.rgb = developBase(source.rgb);
    float amount = uStrength * (1.0 - uCompare);
    gl_FragColor = vec4(mix(source.rgb, grade(source.rgb), amount), source.a);
}
