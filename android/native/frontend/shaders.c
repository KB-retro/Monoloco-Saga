// Single-pass filters from libretro's glsl-shaders, ported to GLSL ES 3 with
// their default parameters fixed. The frame is BGRA, so every fetch swaps it
// back to RGB. Licenses are kept with each shader below.
#include "shaders.h"

#define HEADER                                                     \
  "#version 300 es\n"                                              \
  "precision highp float;\n"                                       \
  "precision highp int;\n"                                         \
  "in vec2 vUv;\n"                                                 \
  "out vec4 fragColor;\n"                                          \
  "uniform sampler2D uTexture;\n"                                  \
  "uniform vec2 uSource;\n"                                        \
  "uniform vec2 uOutput;\n"                                        \
  "vec3 T(vec2 uv) { return texture(uTexture, uv).bgr; }\n"

/*
   Hyllian's xBR-lv2 Shader

   Copyright (C) 2011-2016 Hyllian - sergiogdb@gmail.com

   Permission is hereby granted, free of charge, to any person obtaining a copy
   of this software and associated documentation files (the "Software"), to deal
   in the Software without restriction, including without limitation the rights
   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
   copies of the Software, and to permit persons to whom the Software is
   furnished to do so, subject to the following conditions:

   The above copyright notice and this permission notice shall be included in
   all copies or substantial portions of the Software.

   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
   THE SOFTWARE.

   Incorporates some of the ideas from SABR shader. Thanks to Joshua Street.
*/
// Corner C with smooth tips, as upstream. The edge blend spans one screen
// pixel (upstream fixes it at a third of a source pixel for a 3x pass).
const char *const kXbrFragment =
    HEADER
    "const vec3 rgbw = vec3(14.352, 28.176, 5.472);\n"
    "const vec4 Ao = vec4(1.0, -1.0, -1.0, 1.0);\n"
    "const vec4 Bo = vec4(1.0, 1.0, -1.0, -1.0);\n"
    "const vec4 Co = vec4(1.5, 0.5, -0.5, 0.5);\n"
    "const vec4 Ax = vec4(1.0, -1.0, -1.0, 1.0);\n"
    "const vec4 Bx = vec4(0.5, 2.0, -0.5, -2.0);\n"
    "const vec4 Cx = vec4(1.0, 1.0, -0.5, 0.0);\n"
    "const vec4 Ay = vec4(1.0, -1.0, -1.0, 1.0);\n"
    "const vec4 By = vec4(2.0, 0.5, -2.0, -0.5);\n"
    "const vec4 Cy = vec4(2.0, 0.0, -1.0, 0.5);\n"
    "const vec4 Ci = vec4(0.25);\n"
    "const float kEqThreshold = 15.0;\n"
    "const float kLv2 = 2.0;\n"
    "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n"
    "vec4 diff(vec4 A, vec4 B) { return vec4(notEqual(A, B)); }\n"
    "vec4 eq(vec4 A, vec4 B) { return step(df(A, B), vec4(kEqThreshold)); }\n"
    "vec4 neq(vec4 A, vec4 B) { return vec4(1.0) - eq(A, B); }\n"
    "vec4 wd(vec4 a, vec4 b, vec4 c, vec4 d, vec4 e, vec4 f, vec4 g, vec4 h) {\n"
    "  return df(a, b) + df(a, c) + df(d, e) + df(d, f) + 4.0 * df(g, h);\n"
    "}\n"
    "float c_df(vec3 c1, vec3 c2) { vec3 d = abs(c1 - c2); return d.r + d.g + d.b; }\n"
    "void main() {\n"
    "  float scale = max(uOutput.y / uSource.y, 1.0);\n"
    "  vec4 delta = vec4(1.0 / scale);\n"
    "  vec4 delta_l = vec4(0.5 / scale, 1.0 / scale, 0.5 / scale, 1.0 / scale);\n"
    "  vec4 delta_u = delta_l.yxwz;\n"
    "  float dx = 1.0 / uSource.x;\n"
    "  float dy = 1.0 / uSource.y;\n"
    "  vec2 texel = vUv * uSource;\n"
    "  vec2 fp = fract(texel);\n"
    "  vec2 tc = (floor(texel) + 0.5) / uSource;\n"
    "  vec4 t1 = tc.xxxy + vec4(-dx, 0.0, dx, -2.0 * dy);\n"
    "  vec4 t2 = tc.xxxy + vec4(-dx, 0.0, dx, -dy);\n"
    "  vec4 t3 = tc.xxxy + vec4(-dx, 0.0, dx, 0.0);\n"
    "  vec4 t4 = tc.xxxy + vec4(-dx, 0.0, dx, dy);\n"
    "  vec4 t5 = tc.xxxy + vec4(-dx, 0.0, dx, 2.0 * dy);\n"
    "  vec4 t6 = tc.xyyy + vec4(-2.0 * dx, -dy, 0.0, dy);\n"
    "  vec4 t7 = tc.xyyy + vec4(2.0 * dx, -dy, 0.0, dy);\n"
    "  vec3 A1 = T(t1.xw), B1 = T(t1.yw), C1 = T(t1.zw);\n"
    "  vec3 A = T(t2.xw), B = T(t2.yw), C = T(t2.zw);\n"
    "  vec3 D = T(t3.xw), E = T(t3.yw), F = T(t3.zw);\n"
    "  vec3 G = T(t4.xw), H = T(t4.yw), I = T(t4.zw);\n"
    "  vec3 G5 = T(t5.xw), H5 = T(t5.yw), I5 = T(t5.zw);\n"
    "  vec3 A0 = T(t6.xy), D0 = T(t6.xz), G0 = T(t6.xw);\n"
    "  vec3 C4 = T(t7.xy), F4 = T(t7.xz), I4 = T(t7.xw);\n"
    "  vec4 b = vec4(dot(B, rgbw), dot(D, rgbw), dot(H, rgbw), dot(F, rgbw));\n"
    "  vec4 c = vec4(dot(C, rgbw), dot(A, rgbw), dot(G, rgbw), dot(I, rgbw));\n"
    "  vec4 d = b.yzwx;\n"
    "  vec4 e = vec4(dot(E, rgbw));\n"
    "  vec4 f = b.wxyz;\n"
    "  vec4 g = c.zwxy;\n"
    "  vec4 h = b.zwxy;\n"
    "  vec4 i = c.wxyz;\n"
    "  vec4 i4 = vec4(dot(I4, rgbw), dot(C1, rgbw), dot(A0, rgbw), dot(G5, rgbw));\n"
    "  vec4 i5 = vec4(dot(I5, rgbw), dot(C4, rgbw), dot(A1, rgbw), dot(G0, rgbw));\n"
    "  vec4 h5 = vec4(dot(H5, rgbw), dot(F4, rgbw), dot(B1, rgbw), dot(D0, rgbw));\n"
    "  vec4 f4 = h5.yzwx;\n"
    "  vec4 fx = Ao * fp.y + Bo * fp.x;\n"
    "  vec4 fx_l = Ax * fp.y + Bx * fp.x;\n"
    "  vec4 fx_u = Ay * fp.y + By * fp.x;\n"
    "  vec4 irlv0 = diff(e, f) * diff(e, h);\n"
    "  vec4 irlv1 = irlv0 * (neq(f, b) * neq(f, c) + neq(h, d) * neq(h, g)\n"
    "      + eq(e, i) * (neq(f, f4) * neq(f, i4) + neq(h, h5) * neq(h, i5)) + eq(e, g) + eq(e, c));\n"
    "  vec4 irlv2l = diff(e, g) * diff(d, g);\n"
    "  vec4 irlv2u = diff(e, c) * diff(b, c);\n"
    "  vec4 fx45i = clamp((fx + delta - Co - Ci) / (2.0 * delta), 0.0, 1.0);\n"
    "  vec4 fx45 = clamp((fx + delta - Co) / (2.0 * delta), 0.0, 1.0);\n"
    "  vec4 fx30 = clamp((fx_l + delta_l - Cx) / (2.0 * delta_l), 0.0, 1.0);\n"
    "  vec4 fx60 = clamp((fx_u + delta_u - Cy) / (2.0 * delta_u), 0.0, 1.0);\n"
    "  vec4 wd1 = wd(e, c, g, i, h5, f4, h, f);\n"
    "  vec4 wd2 = wd(h, d, i5, f, i4, b, e, i);\n"
    "  vec4 edri = step(wd1, wd2) * irlv0;\n"
    "  vec4 edr = step(wd1 + vec4(0.1), wd2) * step(vec4(0.5), irlv1);\n"
    "  vec4 edr_l = step(kLv2 * df(f, g), df(h, c)) * irlv2l * edr;\n"
    "  vec4 edr_u = step(kLv2 * df(h, c), df(f, g)) * irlv2u * edr;\n"
    "  fx45 = edr * fx45;\n"
    "  fx30 = edr_l * fx30;\n"
    "  fx60 = edr_u * fx60;\n"
    "  fx45i = edri * fx45i;\n"
    "  vec4 px = step(df(e, f), df(e, h));\n"
    "  vec4 maximos = max(max(fx30, fx60), max(fx45, fx45i));\n"
    "  vec3 res1 = E;\n"
    "  res1 = mix(res1, mix(H, F, px.x), maximos.x);\n"
    "  res1 = mix(res1, mix(B, D, px.z), maximos.z);\n"
    "  vec3 res2 = E;\n"
    "  res2 = mix(res2, mix(F, B, px.y), maximos.y);\n"
    "  res2 = mix(res2, mix(D, H, px.w), maximos.w);\n"
    "  vec3 res = mix(res1, res2, step(c_df(E, res1), c_df(E, res2)));\n"
    "  fragColor = vec4(res, 1.0);\n"
    "}\n";

/*
   Hyllian's CRT Shader

   Copyright (C) 2011-2020 Hyllian - sergiogdb@gmail.com

   Permission is hereby granted, free of charge, to any person obtaining a copy
   of this software and associated documentation files (the "Software"), to deal
   in the Software without restriction, including without limitation the rights
   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
   copies of the Software, and to permit persons to whom the Software is
   furnished to do so, subject to the following conditions:

   The above copyright notice and this permission notice shall be included in
   all copies or substantial portions of the Software.

   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
   THE SOFTWARE.
*/
// Upstream defaults: custom beam profile, Catmull-Rom horizontal filter with
// anti-ringing, and phosphor layout 4 of hunterk's mask collection (public
// domain), an aperture grille for RBG panels.
const char *const kCrtHyllianFragment =
    HEADER
    "const vec4 profile = vec4(0.58, 0.86, 1.0, 1.25);\n"
    "const float kMaskIntensity = 0.5;\n"
    "const float kInputGamma = 2.4;\n"
    "const float kOutputGamma = 2.2;\n"
    "const float B = 0.0;\n"
    "const float C = 0.5;\n"
    "const mat4 invX = mat4(\n"
    "    (-B - 6.0 * C) / 6.0, (12.0 - 9.0 * B - 6.0 * C) / 6.0, -(12.0 - 9.0 * B - 6.0 * C) / 6.0, (B + 6.0 * C) / 6.0,\n"
    "    (3.0 * B + 12.0 * C) / 6.0, (-18.0 + 12.0 * B + 6.0 * C) / 6.0, (18.0 - 15.0 * B - 12.0 * C) / 6.0, -C,\n"
    "    (-3.0 * B - 6.0 * C) / 6.0, 0.0, (3.0 * B + 6.0 * C) / 6.0, 0.0,\n"
    "    B / 6.0, (6.0 - 2.0 * B) / 6.0, B / 6.0, 0.0);\n"
    "vec4 S(vec2 uv) { return pow(vec4(T(uv), 1.0), vec4(kInputGamma)); }\n"
    "vec3 mask_weights(vec2 coord) {\n"
    "  float off = 1.0 - kMaskIntensity;\n"
    "  return mix(vec3(1.0, 1.0, off), vec3(off, off, 1.0), floor(mod(coord.x, 2.0)));\n"
    "}\n"
    "void main() {\n"
    "  vec2 dx = vec2(1.0 / uSource.x, 0.0);\n"
    "  vec2 dy = vec2(0.0, 1.0 / uSource.y);\n"
    "  vec2 pix_coord = vUv * uSource + vec2(-0.5, 0.5);\n"
    "  vec2 tc = (floor(pix_coord) + vec2(0.5)) / uSource;\n"
    "  vec2 fp = fract(pix_coord);\n"
    "  vec4 c00 = S(tc - dx - dy);\n"
    "  vec4 c01 = S(tc - dy);\n"
    "  vec4 c02 = S(tc + dx - dy);\n"
    "  vec4 c03 = S(tc + 2.0 * dx - dy);\n"
    "  vec4 c10 = S(tc - dx);\n"
    "  vec4 c11 = S(tc);\n"
    "  vec4 c12 = S(tc + dx);\n"
    "  vec4 c13 = S(tc + 2.0 * dx);\n"
    "  mat4 color_matrix0 = mat4(c00, c01, c02, c03);\n"
    "  mat4 color_matrix1 = mat4(c10, c11, c12, c13);\n"
    "  vec4 lobes = vec4(fp.x * fp.x * fp.x, fp.x * fp.x, fp.x, 1.0);\n"
    "  vec4 invX_Px = invX * lobes;\n"
    "  vec4 color0 = clamp(color_matrix0 * invX_Px, min(c01, c02), max(c01, c02));\n"
    "  vec4 color1 = clamp(color_matrix1 * invX_Px, min(c11, c12), max(c11, c12));\n"
    "  float pos0 = fp.y;\n"
    "  float pos1 = 1.0 - fp.y;\n"
    "  vec4 lum0 = mix(vec4(profile.y), vec4(profile.z), color0);\n"
    "  vec4 lum1 = mix(vec4(profile.y), vec4(profile.z), color1);\n"
    "  vec4 d0 = 4.0 * profile.x * pos0 / (lum0 + 0.0000001);\n"
    "  vec4 d1 = 4.0 * profile.x * pos1 / (lum1 + 0.0000001);\n"
    "  d0 = exp(-d0 * d0);\n"
    "  d1 = exp(-d1 * d1);\n"
    "  vec4 color = profile.w * (color0 * d0 + color1 * d1);\n"
    "  color.rgb *= mask_weights(gl_FragCoord.xy);\n"
    "  color = pow(max(color, vec4(0.0)), vec4(1.0 / kOutputGamma));\n"
    "  fragColor = vec4(color.rgb, 1.0);\n"
    "}\n";
