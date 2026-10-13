package com.khumasi.game.render

/** GLSL ES 3.00 sources. Colors arrive as sRGB and are lit in (approximately) linear space. */
internal object Shaders {

    private const val COMMON = """
vec3 toLinear(vec3 c) { return c * c; }
vec3 toSrgb(vec3 c) { return sqrt(max(c, vec3(0.0))); }
"""

    private const val SHADOW = """
uniform highp sampler2DShadow uShadowMap;
uniform float uShadowOn;
uniform vec2 uShadowTexel;
uniform float uShadowBias;
float shadowAt(vec4 lp) {
    if (uShadowOn < 0.5) return 1.0;
    vec3 p = lp.xyz / lp.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    p.z -= uShadowBias;
    vec2 t = uShadowTexel * 1.25;
    float s = texture(uShadowMap, p);
    s += texture(uShadowMap, p + vec3(-t.x, -t.y, 0.0));
    s += texture(uShadowMap, p + vec3( t.x, -t.y, 0.0));
    s += texture(uShadowMap, p + vec3(-t.x,  t.y, 0.0));
    s += texture(uShadowMap, p + vec3( t.x,  t.y, 0.0));
    return s / 5.0;
}
"""

    // ---------------------------------------------------------------- letter tiles

    const val TILE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
uniform mat4 uModel;
uniform mat4 uViewProj;
uniform mat4 uLightViewProj;
out vec3 vWorldPos;
out vec3 vNormal;
out vec3 vObjPos;
out vec3 vObjNormal;
out vec4 vLightPos;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vWorldPos = wp.xyz;
    vNormal = mat3(uModel) * aNormal;
    vObjPos = aPos;
    vObjNormal = aNormal;
    vLightPos = uLightViewProj * wp;
    gl_Position = uViewProj * wp;
}
"""

    val TILE_FS = """#version 300 es
precision highp float;
$COMMON
$SHADOW
in vec3 vWorldPos;
in vec3 vNormal;
in vec3 vObjPos;
in vec3 vObjNormal;
in vec4 vLightPos;
uniform vec3 uFrontColor;
uniform vec3 uFrontInk;
uniform vec3 uBackColor;
uniform vec3 uBackInk;
uniform vec4 uFrontCell;   // atlas rect (x, y, w, h); w = 0 means no letter
uniform vec4 uBackCell;
uniform vec3 uHalf;        // tile half extents
uniform float uSymbol;     // reserved: color-blind symbols (phase 2)
uniform sampler2D uAtlas;
uniform vec3 uLightDir;
uniform vec3 uSunColor;
uniform vec3 uCamPos;
uniform vec3 uSkyColor;
uniform vec3 uGroundColor;
uniform float uGlow;
out vec4 fragColor;

float letterMask(vec4 cell, vec2 uv) {
    if (cell.z <= 0.0) return 0.0;
    uv = clamp(uv, 0.0, 1.0);
    return texture(uAtlas, cell.xy + uv * cell.zw).r;
}

void main() {
    vec3 n = normalize(vNormal);
    float nz = vObjNormal.z;
    bool front = nz >= 0.0;
    vec3 base = front ? uFrontColor : uBackColor;
    vec3 ink = front ? uFrontInk : uBackInk;
    float faceMask = smoothstep(0.90, 0.985, abs(nz));
    vec2 uv = vec2(vObjPos.x / (2.0 * uHalf.x) + 0.5, 0.5 - vObjPos.y / (2.0 * uHalf.y));
    if (!front) uv.y = 1.0 - uv.y; // the back face is read after a half turn about X
    float m = letterMask(front ? uFrontCell : uBackCell, uv) * faceMask;
    float edge = 1.0 - 0.22 * (1.0 - abs(nz));
    vec3 albedo = toLinear(mix(base, ink, m)) * edge;

    vec3 L = normalize(uLightDir);
    vec3 V = normalize(uCamPos - vWorldPos);
    vec3 H = normalize(L + V);
    float sh = shadowAt(vLightPos);
    float ndl = max(dot(n, L), 0.0);
    float hemi = 0.5 + 0.5 * n.y;
    vec3 ambient = mix(toLinear(uGroundColor), toLinear(uSkyColor), hemi) * albedo * 0.62;
    vec3 diffuse = albedo * toLinear(uSunColor) * ndl * mix(0.35, 1.0, sh) * 0.85;
    float gloss = mix(0.30, 0.12, m);
    float spec = pow(max(dot(n, H), 0.0), 56.0) * gloss * sh;
    float fres = pow(1.0 - max(dot(n, V), 0.0), 4.0) * 0.10;
    vec3 col = ambient + diffuse + vec3(spec) + fres * toLinear(uSkyColor);
    col += albedo * uGlow * 0.45;
    fragColor = vec4(toSrgb(col), 1.0);
}
"""

    // ---------------------------------------------------------------- the board (glass slab)

    val SLAB_FS = """#version 300 es
precision highp float;
$COMMON
$SHADOW
in vec3 vWorldPos;
in vec3 vNormal;
in vec3 vObjPos;
in vec3 vObjNormal;
in vec4 vLightPos;
uniform vec3 uTint;
uniform vec3 uSocketColor;
uniform vec3 uActiveColor;
uniform float uPitch;
uniform vec2 uCellHalf;
uniform float uCellRadius;
uniform float uCols;
uniform float uRows;
uniform float uActiveRow;
uniform float uPulse;
uniform sampler2D uBlurTex;
uniform float uUseBlur;
uniform vec2 uScreenSize;
uniform vec3 uLightDir;
uniform vec3 uSunColor;
uniform vec3 uCamPos;
uniform vec3 uSkyColor;
uniform vec3 uGroundColor;
out vec4 fragColor;

float sdRoundRect(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec3 n = normalize(vNormal);
    vec3 tint = uTint;
    if (uUseBlur > 0.5) {
        vec3 bg = texture(uBlurTex, gl_FragCoord.xy / uScreenSize).rgb;
        tint = mix(bg, uTint, 0.66);
    }
    // gentle vertical gradient
    tint *= 0.92 + 0.10 * clamp(vObjPos.y * 0.08 + 0.5, 0.0, 1.0);
    vec3 albedo = toLinear(tint);

    if (vObjNormal.z > 0.9) {
        float gx = clamp(floor((uCols - 1.0) * 0.5 - vObjPos.x / uPitch + 0.5), 0.0, uCols - 1.0);
        float gy = clamp(floor((uRows - 1.0) * 0.5 - vObjPos.y / uPitch + 0.5), 0.0, uRows - 1.0);
        vec2 center = vec2(((uCols - 1.0) * 0.5 - gx) * uPitch, ((uRows - 1.0) * 0.5 - gy) * uPitch);
        vec2 local = vObjPos.xy - center;
        float d = sdRoundRect(local, uCellHalf, uCellRadius);
        float inside = smoothstep(0.015, -0.015, d);
        bool active = abs(gy - uActiveRow) < 0.5;
        vec3 socket = toLinear(active ? mix(uSocketColor, uActiveColor, 0.35 + 0.15 * uPulse) : uSocketColor);
        // inner shadow along the upper-left wall, light along the lower-right lip
        float wall = smoothstep(-0.16, 0.0, d);
        float dirShade = clamp(0.5 + (local.y / uCellHalf.y) * 0.35 - (local.x / uCellHalf.x) * 0.15, 0.0, 1.0);
        socket *= 1.0 - wall * 0.55 * dirShade;
        float lip = smoothstep(0.0, 0.02, d) * smoothstep(0.06, 0.02, d);
        albedo = mix(albedo, socket, inside);
        albedo += lip * 0.05 * (active ? toLinear(uActiveColor) : vec3(1.0));
        if (active) {
            float ring = smoothstep(0.05, 0.0, abs(d + 0.01));
            albedo += ring * toLinear(uActiveColor) * (0.35 + 0.25 * uPulse);
        }
    }

    vec3 L = normalize(uLightDir);
    vec3 V = normalize(uCamPos - vWorldPos);
    vec3 H = normalize(L + V);
    float sh = shadowAt(vLightPos);
    float ndl = max(dot(n, L), 0.0);
    vec3 ambient = mix(toLinear(uGroundColor), toLinear(uSkyColor), 0.5 + 0.5 * n.y) * albedo * 0.75;
    vec3 diffuse = albedo * toLinear(uSunColor) * ndl * mix(0.25, 1.0, sh) * 0.55;
    float spec = pow(max(dot(n, H), 0.0), 90.0) * 0.18 * sh;
    float fres = pow(1.0 - max(dot(n, V), 0.0), 3.0) * 0.08;
    vec3 col = ambient * mix(0.55, 1.0, sh) + diffuse + vec3(spec) + fres * toLinear(uSkyColor);
    fragColor = vec4(toSrgb(col), 1.0);
}
"""

    // ---------------------------------------------------------------- shadow depth pass

    const val DEPTH_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
uniform mat4 uModel;
uniform mat4 uLightViewProj;
void main() {
    gl_Position = uLightViewProj * uModel * vec4(aPos, 1.0);
}
"""

    const val DEPTH_FS = """#version 300 es
precision mediump float;
void main() { }
"""

    // ---------------------------------------------------------------- sky

    const val SKY_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
out vec2 vNdc;
void main() {
    vNdc = aPos.xy * 2.0;
    gl_Position = vec4(aPos.xy * 2.0, 0.9999, 1.0);
}
"""

    val SKY_FS = """#version 300 es
precision highp float;
$COMMON
in vec2 vNdc;
uniform vec3 uZenith;
uniform vec3 uHorizon;
uniform vec3 uSunColor;
uniform vec2 uSunNdc;
uniform float uHorizonY;
uniform float uAspect;
out vec4 fragColor;
void main() {
    float t = clamp((vNdc.y - uHorizonY) / (1.0 - uHorizonY), 0.0, 1.0);
    vec3 col = mix(toLinear(uHorizon), toLinear(uZenith), pow(t, 0.75));
    vec2 d = (vNdc - uSunNdc) * vec2(uAspect, 1.0);
    float r = length(d);
    col += toLinear(uSunColor) * (exp(-r * 9.0) * 0.9 + exp(-r * 2.2) * 0.22);
    fragColor = vec4(toSrgb(col), 1.0);
}
"""

    // ---------------------------------------------------------------- sea

    const val SEA_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
uniform mat4 uViewProj;
uniform mat4 uLightViewProj;
out vec3 vWorldPos;
out vec4 vLightPos;
void main() {
    vec4 wp = vec4(aPos, 1.0);
    vWorldPos = wp.xyz;
    vLightPos = uLightViewProj * wp;
    gl_Position = uViewProj * wp;
}
"""

    val SEA_FS = """#version 300 es
precision highp float;
$COMMON
$SHADOW
in vec3 vWorldPos;
in vec4 vLightPos;
uniform float uTime;
uniform vec3 uDeep;
uniform vec3 uShallow;
uniform vec3 uFoam;
uniform vec3 uHorizon;
uniform vec3 uZenith;
uniform vec3 uSunColor;
uniform vec3 uLightDir;
uniform vec3 uCamPos;
uniform float uIslandRadius;
uniform float uFogStart;
uniform float uFogEnd;
out vec4 fragColor;
void main() {
    vec2 p = vWorldPos.xz;
    float t = uTime;
    float dx = 0.10 * cos(p.x * 0.9 + t * 1.1) + 0.06 * cos(p.x * 1.7 - p.y * 1.3 + t * 1.7) + 0.03 * cos((p.x + p.y) * 3.1 + t * 2.3);
    float dz = 0.10 * cos(p.y * 0.8 + t * 0.9) + 0.06 * cos(p.y * 1.9 + p.x * 1.1 - t * 1.5) + 0.03 * cos((p.y - p.x) * 2.9 - t * 2.1);
    vec3 n = normalize(vec3(-dx, 1.0, -dz));
    float r = length(p);
    float shore = 1.0 - smoothstep(uIslandRadius * 0.95, uIslandRadius * 1.9, r);
    vec3 water = mix(toLinear(uDeep), toLinear(uShallow), shore);
    vec3 V = normalize(uCamPos - vWorldPos);
    vec3 L = normalize(uLightDir);
    float fres = 0.04 + 0.96 * pow(1.0 - max(dot(n, V), 0.0), 5.0);
    vec3 sky = mix(toLinear(uHorizon), toLinear(uZenith), 0.35);
    vec3 col = mix(water, sky, fres * 0.85);
    float sh = shadowAt(vLightPos);
    vec3 H = normalize(L + V);
    col += toLinear(uSunColor) * pow(max(dot(n, H), 0.0), 220.0) * 1.4 * sh;
    float wave = sin(r * 5.5 - t * 1.6 + sin(atan(p.y, p.x) * 7.0) * 0.6) * 0.5 + 0.5;
    float foamBand = smoothstep(uIslandRadius * 1.08, uIslandRadius * 0.98, r) * smoothstep(uIslandRadius * 0.82, uIslandRadius * 0.95, r);
    col = mix(col, toLinear(uFoam), foamBand * (0.55 + 0.45 * wave));
    col *= mix(0.72, 1.0, sh);
    float fog = smoothstep(uFogStart, uFogEnd, length(vWorldPos - uCamPos));
    col = mix(col, toLinear(uHorizon), fog);
    fragColor = vec4(toSrgb(col), 1.0);
}
"""

    // ---------------------------------------------------------------- island objects

    const val ISLAND_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
uniform mat4 uModel;
uniform mat4 uViewProj;
uniform mat4 uLightViewProj;
uniform float uTime;
uniform float uWind;
out vec3 vWorldPos;
out vec3 vNormal;
out vec3 vColor;
out vec4 vLightPos;
void main() {
    vec3 p = aPos;
    float sway = aColor.a * uWind;
    p.x += sin(uTime * 1.7 + aPos.x * 0.8 + aPos.z * 0.6) * 0.05 * sway * aPos.y;
    p.z += cos(uTime * 1.3 + aPos.z * 0.7) * 0.035 * sway * aPos.y;
    vec4 wp = uModel * vec4(p, 1.0);
    vWorldPos = wp.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = aColor.rgb;
    vLightPos = uLightViewProj * wp;
    gl_Position = uViewProj * wp;
}
"""

    val ISLAND_FS = """#version 300 es
precision highp float;
$COMMON
$SHADOW
in vec3 vWorldPos;
in vec3 vNormal;
in vec3 vColor;
in vec4 vLightPos;
uniform vec3 uLightDir;
uniform vec3 uSunColor;
uniform vec3 uSkyColor;
uniform vec3 uGroundColor;
uniform vec3 uHorizon;
uniform vec3 uCamPos;
uniform float uFogStart;
uniform float uFogEnd;
uniform float uEmissive;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 albedo = toLinear(vColor);
    vec3 L = normalize(uLightDir);
    float sh = shadowAt(vLightPos);
    float ndl = max(dot(n, L), 0.0);
    vec3 ambient = mix(toLinear(uGroundColor), toLinear(uSkyColor), 0.5 + 0.5 * n.y) * albedo * 0.55;
    vec3 diffuse = albedo * toLinear(uSunColor) * ndl * sh * 0.95;
    vec3 V = normalize(uCamPos - vWorldPos);
    float rim = pow(1.0 - max(dot(n, V), 0.0), 3.0) * 0.12;
    vec3 col = ambient + diffuse + rim * toLinear(uSkyColor) + albedo * uEmissive;
    float fog = smoothstep(uFogStart, uFogEnd, length(vWorldPos - uCamPos));
    col = mix(col, toLinear(uHorizon), fog);
    fragColor = vec4(toSrgb(col), 1.0);
}
"""

    // ---------------------------------------------------------------- blur (frosted glass)

    const val BLUR_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
out vec2 vUv;
void main() {
    vUv = aPos.xy + 0.5;
    gl_Position = vec4(aPos.xy * 2.0, 0.0, 1.0);
}
"""

    const val BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uStep;
out vec4 fragColor;
void main() {
    vec3 c = texture(uTex, vUv).rgb * 0.227027;
    c += texture(uTex, vUv + uStep * 1.384615).rgb * 0.316216;
    c += texture(uTex, vUv - uStep * 1.384615).rgb * 0.316216;
    c += texture(uTex, vUv + uStep * 3.230769).rgb * 0.070270;
    c += texture(uTex, vUv - uStep * 3.230769).rgb * 0.070270;
    fragColor = vec4(c, 1.0);
}
"""
}
