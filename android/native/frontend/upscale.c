// FSR and Anime4K. Every pass draws a full-screen triangle strip into a
// texture; positions come from gl_FragCoord, so row 0 stays the top of the
// picture in every texture, as in the core's frame. The last pass (RCAS)
// draws the picture's own quad onto the screen.
#include "upscale.h"

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "anime4k_shaders.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Monoloco", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Monoloco", __VA_ARGS__)
#define COUNT(a) ((int)(sizeof(a) / sizeof((a)[0])))

enum { kMaxBinds = 8, kMaxPasses = 16, kMaxRuns = 2, kNetworks = 2 };

typedef struct {
  GLuint texture;
  int width, height;
} Target;

typedef struct {
  GLuint program;
  GLint output, sharpness, raw[kMaxBinds], size[kMaxBinds];
} Pass;

// Each network doubles the picture and runs twice (x4), in kUpscale order.
static const struct {
  const HookPass *passes;
  int count, runs;
} kNetworkTable[kNetworks] = {
    {kAnime4kLightPasses, COUNT(kAnime4kLightPasses), 2},
    {kAnime4kFullPasses, COUNT(kAnime4kFullPasses), 2},
};

static GLuint s_full_vao, s_full_vbo, s_framebuffer;
static Pass s_unpack, s_easu, s_rcas;
static Pass s_network[kNetworks][kMaxPasses];
static int s_network_state[kNetworks];  // 0 not built, 1 ready, -1 failed
static Target s_source, s_mid, s_network_target[kNetworks][kMaxRuns][kMaxPasses];
static bool s_ready, s_half_float;

static const char *kFullVertex =
    "#version 300 es\n"
    "layout(location = 0) in vec2 aPosition;\n"
    "void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }\n";

static const char *kPictureVertex =
    "#version 300 es\n"
    "layout(location = 0) in vec2 aPosition;\n"
    "layout(location = 1) in vec2 aUv;\n"
    "out vec2 vUv;\n"
    "void main() { vUv = aUv; gl_Position = vec4(aPosition, 0.0, 1.0); }\n";

#define HEADER                       \
  "#version 300 es\n"                \
  "precision highp float;\n"         \
  "out vec4 fragColor;\n"            \
  "uniform vec2 uOutput;\n"          \
  "uniform sampler2D S_raw;\n"       \
  "uniform vec2 S_size;\n"

// The core's BGRA frame as RGB, at its own size.
static const char *kUnpack =
    HEADER
    "void main() { fragColor = vec4(texture(S_raw, gl_FragCoord.xy / uOutput).bgr, 1.0); }\n";

/*
   FSR - [EASU] EDGE ADAPTIVE SPATIAL UPSAMPLING and [RCAS] ROBUST CONTRAST
   ADAPTIVE SHARPENING, from libretro's glsl-shaders (fsr/shaders), ported from
   https://www.shadertoy.com/view/stXSWB, MIT license.

   Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.

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
static const char *kEasu =
    HEADER
    "vec3 F(vec2 p) { return texture(S_raw, p).rgb; }\n"
    "void Tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {\n"
    "  vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x)));\n"
    "  v *= len;\n"
    "  float d2 = min(dot(v, v), clp);\n"
    "  float wB = .4 * d2 - 1.;\n"
    "  float wA = lob * d2 - 1.;\n"
    "  wB *= wB;\n"
    "  wA *= wA;\n"
    "  wB = 1.5625 * wB - .5625;\n"
    "  float w = wB * wA;\n"
    "  aC += c * w;\n"
    "  aW += w;\n"
    "}\n"
    "void Set(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {\n"
    "  float lenX = max(abs(lD - lC), abs(lC - lB));\n"
    "  float dirX = lD - lB;\n"
    "  dir.x += dirX * w;\n"
    "  lenX = clamp(abs(dirX) / lenX, 0., 1.);\n"
    "  lenX *= lenX;\n"
    "  len += lenX * w;\n"
    "  float lenY = max(abs(lE - lC), abs(lC - lA));\n"
    "  float dirY = lE - lA;\n"
    "  dir.y += dirY * w;\n"
    "  lenY = clamp(abs(dirY) / lenY, 0., 1.);\n"
    "  lenY *= lenY;\n"
    "  len += lenY * w;\n"
    "}\n"
    "float Luma(vec3 c) { return c.g + .5 * (c.r + c.b); }\n"
    "void main() {\n"
    "  vec2 ip = floor(gl_FragCoord.xy);\n"
    "  vec4 con0 = vec4(S_size / uOutput, .5 * S_size / uOutput - .5);\n"
    "  vec4 con1 = vec4(1, 1, 1, -1) / S_size.xyxy;\n"
    "  vec4 con2 = vec4(-1, 2, 1, 2) / S_size.xyxy;\n"
    "  vec4 con3 = vec4(0, 4, 0, 0) / S_size.xyxy;\n"
    "  vec2 pp = ip * con0.xy + con0.zw;\n"
    "  vec2 fp = floor(pp);\n"
    "  pp -= fp;\n"
    "  vec2 p0 = fp * con1.xy + con1.zw;\n"
    "  vec2 p1 = p0 + con2.xy;\n"
    "  vec2 p2 = p0 + con2.zw;\n"
    "  vec2 p3 = p0 + con3.xy;\n"
    "  vec4 off = vec4(-.5, .5, -.5, .5) * con1.xxyy;\n"
    "  vec3 bC = F(p0 + off.xw); float bL = Luma(bC);\n"
    "  vec3 cC = F(p0 + off.yw); float cL = Luma(cC);\n"
    "  vec3 iC = F(p1 + off.xw); float iL = Luma(iC);\n"
    "  vec3 jC = F(p1 + off.yw); float jL = Luma(jC);\n"
    "  vec3 fC = F(p1 + off.yz); float fL = Luma(fC);\n"
    "  vec3 eC = F(p1 + off.xz); float eL = Luma(eC);\n"
    "  vec3 kC = F(p2 + off.xw); float kL = Luma(kC);\n"
    "  vec3 lC = F(p2 + off.yw); float lL = Luma(lC);\n"
    "  vec3 hC = F(p2 + off.yz); float hL = Luma(hC);\n"
    "  vec3 gC = F(p2 + off.xz); float gL = Luma(gC);\n"
    "  vec3 oC = F(p3 + off.yz); float oL = Luma(oC);\n"
    "  vec3 nC = F(p3 + off.xz); float nL = Luma(nC);\n"
    "  vec2 dir = vec2(0);\n"
    "  float len = 0.;\n"
    "  Set(dir, len, (1. - pp.x) * (1. - pp.y), bL, eL, fL, gL, jL);\n"
    "  Set(dir, len, pp.x * (1. - pp.y), cL, fL, gL, hL, kL);\n"
    "  Set(dir, len, (1. - pp.x) * pp.y, fL, iL, jL, kL, nL);\n"
    "  Set(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);\n"
    "  vec2 dir2 = dir * dir;\n"
    "  float dirR = dir2.x + dir2.y;\n"
    "  bool zro = dirR < (1.0 / 32768.0);\n"
    "  dirR = inversesqrt(dirR);\n"
    "  dirR = zro ? 1.0 : dirR;\n"
    "  dir.x = zro ? 1.0 : dir.x;\n"
    "  dir *= vec2(dirR);\n"
    "  len = len * 0.5;\n"
    "  len *= len;\n"
    "  float stretch = dot(dir, dir) / (max(abs(dir.x), abs(dir.y)));\n"
    "  vec2 len2 = vec2(1. + (stretch - 1.0) * len, 1. - .5 * len);\n"
    "  float lob = .5 - .29 * len;\n"
    "  float clp = 1. / lob;\n"
    "  vec3 min4 = min(min(fC, gC), min(jC, kC));\n"
    "  vec3 max4 = max(max(fC, gC), max(jC, kC));\n"
    "  vec3 aC = vec3(0);\n"
    "  float aW = 0.;\n"
    "  Tap(aC, aW, vec2(0, -1) - pp, dir, len2, lob, clp, bC);\n"
    "  Tap(aC, aW, vec2(1, -1) - pp, dir, len2, lob, clp, cC);\n"
    "  Tap(aC, aW, vec2(-1, 1) - pp, dir, len2, lob, clp, iC);\n"
    "  Tap(aC, aW, vec2(0, 1) - pp, dir, len2, lob, clp, jC);\n"
    "  Tap(aC, aW, vec2(0, 0) - pp, dir, len2, lob, clp, fC);\n"
    "  Tap(aC, aW, vec2(-1, 0) - pp, dir, len2, lob, clp, eC);\n"
    "  Tap(aC, aW, vec2(1, 1) - pp, dir, len2, lob, clp, kC);\n"
    "  Tap(aC, aW, vec2(2, 1) - pp, dir, len2, lob, clp, lC);\n"
    "  Tap(aC, aW, vec2(2, 0) - pp, dir, len2, lob, clp, hC);\n"
    "  Tap(aC, aW, vec2(1, 0) - pp, dir, len2, lob, clp, gC);\n"
    "  Tap(aC, aW, vec2(1, 2) - pp, dir, len2, lob, clp, oC);\n"
    "  Tap(aC, aW, vec2(0, 2) - pp, dir, len2, lob, clp, nC);\n"
    "  fragColor = vec4(min(max4, max(min4, aC / aW)), 1.0);\n"
    "}\n";

// RCAS on the picture's quad: vUv * uOutput is the pixel in the EASU texture.
static const char *kRcas =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "out vec4 fragColor;\n"
    "uniform vec2 uOutput;\n"
    "uniform sampler2D S_raw;\n"
    "uniform float uSharpness;\n"
    "vec3 L(vec2 p) { return texture(S_raw, p / uOutput).rgb; }\n"
    "void main() {\n"
    "  vec2 sp = vUv * uOutput;\n"
    "  float con = exp2(-uSharpness);\n"
    "  vec3 b = L(sp + vec2(0, -1));\n"
    "  vec3 d = L(sp + vec2(-1, 0));\n"
    "  vec3 e = L(sp);\n"
    "  vec3 f = L(sp + vec2(1, 0));\n"
    "  vec3 h = L(sp + vec2(0, 1));\n"
    "  vec3 mn4 = min(b, min(f, h));\n"
    "  vec3 mx4 = max(b, max(f, h));\n"
    "  vec3 hitMin = mn4 / (4. * mx4);\n"
    "  vec3 hitMax = (1. - mx4) / (4. * mn4 - 4.);\n"
    "  vec3 lobeRGB = max(-hitMin, hitMax);\n"
    "  float lobe = max(-(0.25 - 1.0 / 16.0), min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.)) * con;\n"
    "  fragColor = vec4((lobe * (b + d + h + f) + e) / (4. * lobe + 1.), 1.0);\n"
    "}\n";

static GLuint Compile(GLenum type, const char *source) {
  GLuint shader = glCreateShader(type);
  glShaderSource(shader, 1, &source, NULL);
  glCompileShader(shader);
  GLint ok = 0;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[1024];
    glGetShaderInfoLog(shader, sizeof log, NULL, log);
    LOGE("upscale shader: %s", log);
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

// Links a pass and finds the uniforms of the textures it reads.
static bool Build(Pass *pass, const char *vertex_source, const char *fragment_source,
                  const char *const *binds, int bind_count) {
  memset(pass, 0, sizeof *pass);
  GLuint vertex = Compile(GL_VERTEX_SHADER, vertex_source);
  GLuint fragment = vertex ? Compile(GL_FRAGMENT_SHADER, fragment_source) : 0;
  if (fragment) {
    pass->program = glCreateProgram();
    glAttachShader(pass->program, vertex);
    glAttachShader(pass->program, fragment);
    glLinkProgram(pass->program);
    GLint ok = 0;
    glGetProgramiv(pass->program, GL_LINK_STATUS, &ok);
    if (!ok) {
      LOGE("upscale program did not link");
      glDeleteProgram(pass->program);
      pass->program = 0;
    }
  }
  if (vertex) glDeleteShader(vertex);
  if (fragment) glDeleteShader(fragment);
  if (!pass->program) return false;
  pass->output = glGetUniformLocation(pass->program, "uOutput");
  pass->sharpness = glGetUniformLocation(pass->program, "uSharpness");
  for (int i = 0; i < bind_count; i++) {
    char name[96];
    snprintf(name, sizeof name, "%s_raw", binds[i]);
    pass->raw[i] = glGetUniformLocation(pass->program, name);
    snprintf(name, sizeof name, "%s_size", binds[i]);
    pass->size[i] = glGetUniformLocation(pass->program, name);
  }
  return true;
}

static void Delete(Pass *pass) {
  if (pass->program) glDeleteProgram(pass->program);
  pass->program = 0;
}

// (Re)allocates a render target; half float for the networks' features.
static void Ensure(Target *target, int width, int height, bool half_float, GLint sampling) {
  if (target->texture && target->width == width && target->height == height) return;
  if (!target->texture) glGenTextures(1, &target->texture);
  glBindTexture(GL_TEXTURE_2D, target->texture);
  glTexImage2D(GL_TEXTURE_2D, 0, half_float ? GL_RGBA16F : GL_RGBA8, width, height, 0, GL_RGBA,
               half_float ? GL_HALF_FLOAT : GL_UNSIGNED_BYTE, NULL);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, sampling);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, sampling);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  target->width = width;
  target->height = height;
}

static void Free(Target *target) {
  if (target->texture) glDeleteTextures(1, &target->texture);
  target->texture = 0;
  target->width = target->height = 0;
}

// Draws `pass` into `target` reading `inputs` (one per bind).
static void Run(const Pass *pass, const Target *target, const Target *const *inputs, int count) {
  glBindFramebuffer(GL_FRAMEBUFFER, s_framebuffer);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target->texture, 0);
  glViewport(0, 0, target->width, target->height);
  glUseProgram(pass->program);
  for (int i = 0; i < count; i++) {
    glActiveTexture(GL_TEXTURE0 + i);
    glBindTexture(GL_TEXTURE_2D, inputs[i]->texture);
    glUniform1i(pass->raw[i], i);
    glUniform2f(pass->size[i], (float)inputs[i]->width, (float)inputs[i]->height);
  }
  glUniform2f(pass->output, (float)target->width, (float)target->height);
  glBindVertexArray(s_full_vao);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static bool HasExtension(const char *name) {
  GLint count = 0;
  glGetIntegerv(GL_NUM_EXTENSIONS, &count);
  for (GLint i = 0; i < count; i++) {
    const char *extension = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
    if (extension && !strcmp(extension, name)) return true;
  }
  return false;
}

void UpscaleInit(void) {
  static const char *const kSource[] = {"S"};
  memset(s_network_state, 0, sizeof s_network_state);
  s_ready = Build(&s_unpack, kFullVertex, kUnpack, kSource, 1) &&
            Build(&s_easu, kFullVertex, kEasu, kSource, 1) &&
            Build(&s_rcas, kPictureVertex, kRcas, kSource, 1);
  // The networks keep negative features: they need half-float targets.
  s_half_float = HasExtension("GL_EXT_color_buffer_half_float") ||
                 HasExtension("GL_EXT_color_buffer_float");
  if (!s_half_float) LOGI("no half-float render targets: Anime4K falls back to FSR");
  static const float kQuad[8] = {-1, -1, 1, -1, -1, 1, 1, 1};
  glGenVertexArrays(1, &s_full_vao);
  glGenBuffers(1, &s_full_vbo);
  glBindVertexArray(s_full_vao);
  glBindBuffer(GL_ARRAY_BUFFER, s_full_vbo);
  glBufferData(GL_ARRAY_BUFFER, sizeof kQuad, kQuad, GL_STATIC_DRAW);
  glEnableVertexAttribArray(0);
  glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (void *)0);
  glBindVertexArray(0);
  glGenFramebuffers(1, &s_framebuffer);
}

void UpscaleShutdown(void) {
  Delete(&s_unpack);
  Delete(&s_easu);
  Delete(&s_rcas);
  for (int n = 0; n < kNetworks; n++) {
    for (int p = 0; p < kMaxPasses; p++) Delete(&s_network[n][p]);
    for (int r = 0; r < kMaxRuns; r++)
      for (int p = 0; p < kMaxPasses; p++) Free(&s_network_target[n][r][p]);
    s_network_state[n] = 0;
  }
  Free(&s_source);
  Free(&s_mid);
  if (s_framebuffer) glDeleteFramebuffers(1, &s_framebuffer);
  if (s_full_vbo) glDeleteBuffers(1, &s_full_vbo);
  if (s_full_vao) glDeleteVertexArrays(1, &s_full_vao);
  s_framebuffer = s_full_vbo = s_full_vao = 0;
  s_ready = false;
}

// Builds a network's programs the first time it is chosen.
static bool NetworkReady(int network) {
  if (s_network_state[network]) return s_network_state[network] > 0;
  s_network_state[network] = -1;
  if (!s_half_float || kNetworkTable[network].count > kMaxPasses) return false;
  for (int p = 0; p < kNetworkTable[network].count; p++) {
    const HookPass *pass = &kNetworkTable[network].passes[p];
    if (!Build(&s_network[network][p], kFullVertex, pass->source, pass->binds, pass->bind_count))
      return false;
  }
  s_network_state[network] = 1;
  return true;
}

// Runs a network over `main` (its runs each double it); returns the result.
static const Target *RunNetwork(int network, const Target *main) {
  for (int run = 0; run < kNetworkTable[network].runs; run++) {
    const char *names[kMaxPasses + 1] = {"MAIN"};
    const Target *textures[kMaxPasses + 1] = {main};
    int named = 1;
    for (int p = 0; p < kNetworkTable[network].count; p++) {
      const HookPass *pass = &kNetworkTable[network].passes[p];
      const Target *inputs[8] = {NULL};
      const Target *reference = NULL;
      for (int n = 0; n < named; n++) {
        if (!strcmp(names[n], pass->size_of)) reference = textures[n];
        for (int b = 0; b < pass->bind_count; b++)
          if (!strcmp(names[n], pass->binds[b])) inputs[b] = textures[n];
      }
      for (int b = 0; b < pass->bind_count; b++)
        if (!inputs[b]) return NULL;
      if (!reference) return NULL;
      Target *target = &s_network_target[network][run][p];
      Ensure(target, reference->width * pass->scale, reference->height * pass->scale, true, GL_LINEAR);
      Run(&s_network[network][p], target, inputs, pass->bind_count);
      int slot = 0;
      while (slot < named && strcmp(names[slot], pass->save)) slot++;
      if (slot == named) named++;
      names[slot] = pass->save;
      textures[slot] = target;
    }
    main = textures[0];
  }
  return main;
}

bool UpscaleDraw(int method, GLuint frame, int frame_width, int frame_height,
                 int picture_width, int picture_height, GLuint picture_vao,
                 int drawable_width, int drawable_height) {
  if (!s_ready || picture_width <= 0 || picture_height <= 0) return false;
  int network = method - kUpscaleAnime4kLight;
  if (network >= 0 && !NetworkReady(network)) network = -1;  // FSR alone

  const Target frame_target = {frame, frame_width, frame_height};
  const Target *input = &frame_target;
  glBindTexture(GL_TEXTURE_2D, frame);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  Ensure(&s_source, frame_width, frame_height, false, GL_LINEAR);
  Run(&s_unpack, &s_source, &input, 1);
  const Target *upscaled = &s_source;
  if (network >= 0) {
    upscaled = RunNetwork(network, &s_source);
    if (!upscaled) {
      LOGE("Anime4K network %d is incomplete", network);
      s_network_state[network] = -1;
      upscaled = &s_source;
      network = -1;
    }
  }
  Ensure(&s_mid, picture_width, picture_height, false, GL_NEAREST);
  Run(&s_easu, &s_mid, &upscaled, 1);

  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  glViewport(0, 0, drawable_width, drawable_height);
  glUseProgram(s_rcas.program);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, s_mid.texture);
  glUniform1i(s_rcas.raw[0], 0);
  glUniform2f(s_rcas.output, (float)picture_width, (float)picture_height);
  glUniform1f(s_rcas.sharpness, network >= 0 ? 0.3f : 0.2f);
  glBindVertexArray(picture_vao);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  glBindVertexArray(0);
  return true;
}
