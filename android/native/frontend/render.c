// OpenGL ES 3: one textured quad, one shader with the filter and effect as
// uniforms. The core writes BGRA bytes; the shader swaps them back.
#include "render.h"
#include "frontend.h"

#include <GLES3/gl3.h>
#include <android/log.h>
#include <math.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

// The second game's Reconstruct upscaler (MIT, upstream/game2), used as is
// and translated to GLSL ES 3 when the renderer starts (ReconstructSource).
#include "desktop_reconstruct_shader.h"
#include "shaders.h"
#include "upscale.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Monoloco", __VA_ARGS__)

static GLuint s_program, s_texture, s_vao, s_vbo;
static GLint s_u_texture, s_u_source, s_u_output, s_u_filter, s_u_effect;
static GLuint s_hd_program;
static GLint s_hd_source, s_hd_size, s_hd_output, s_hd_mode, s_hd_strength, s_hd_softness, s_hd_shading;
// xBR, CRT-Lottes and CRT-Hyllian (shaders.c), in filter order.
typedef struct {
  GLuint program;
  GLint texture, source, output;
} ExtraFilter;
static ExtraFilter s_extra[kFilterCrtHyllian - kFilterXbr + 1];
static int s_tex_width, s_tex_height;
static int s_frame_width = 256, s_frame_height = 224;
static bool s_have_frame;

static const char *kVertex =
    "#version 300 es\n"
    "layout(location = 0) in vec2 aPosition;\n"
    "layout(location = 1) in vec2 aUv;\n"
    "out vec2 vUv;\n"
    "void main() { vUv = aUv; gl_Position = vec4(aPosition, 0.0, 1.0); }\n";

// Filters: 0 nearest, 1 linear, 2 sharp bilinear (pixel interiors stay flat,
// only the borders blend). Effects: 0 none, 1 scanlines, 2 CRT (scanlines,
// aperture mask and a soft vignette).
static const char *kFragment =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "out vec4 fragColor;\n"
    "uniform sampler2D uTexture;\n"
    "uniform vec2 uSource;\n"
    "uniform vec2 uOutput;\n"
    "uniform int uFilter;\n"
    "uniform int uEffect;\n"
    "void main() {\n"
    "  vec2 texel = vUv * uSource;\n"
    "  vec2 uv = vUv;\n"
    "  if (uFilter == 0) {\n"
    "    uv = (floor(texel) + 0.5) / uSource;\n"
    "  } else if (uFilter == 2) {\n"
    "    vec2 scale = max(uOutput / uSource, vec2(1.0));\n"
    "    vec2 region = 0.5 - 0.5 / scale;\n"
    "    vec2 center = fract(texel) - 0.5;\n"
    "    vec2 f = (center - clamp(center, -region, region)) * scale + 0.5;\n"
    "    uv = (floor(texel) + f) / uSource;\n"
    "  }\n"
    "  vec3 color = texture(uTexture, uv).bgr;\n"
    "  if (uEffect >= 1) {\n"
    "    float line = 0.5 + 0.5 * cos(6.2831853 * (fract(texel.y) - 0.5));\n"
    "    float strength = uEffect == 1 ? 0.35 : 0.45;\n"
    "    color *= mix(1.0 - strength, 1.0, line);\n"
    "  }\n"
    "  if (uEffect == 2) {\n"
    "    float column = mod(gl_FragCoord.x, 3.0);\n"
    "    vec3 mask = column < 1.0 ? vec3(1.0, 0.82, 0.82)\n"
    "              : column < 2.0 ? vec3(0.82, 1.0, 0.82) : vec3(0.82, 0.82, 1.0);\n"
    "    color *= mask * 1.22;\n"
    "    vec2 edge = vUv * (1.0 - vUv);\n"
    "    color *= clamp(pow(edge.x * edge.y * 16.0, 0.12), 0.0, 1.0);\n"
    "  }\n"
    "  fragColor = vec4(color, 1.0);\n"
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
    LOGE("shader: %s", log);
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

static GLuint Link(const char *vertex_source, const char *fragment_source) {
  GLuint vertex = Compile(GL_VERTEX_SHADER, vertex_source);
  GLuint fragment = fragment_source ? Compile(GL_FRAGMENT_SHADER, fragment_source) : 0;
  GLuint program = 0;
  if (vertex && fragment) {
    program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
      LOGE("shader program did not link");
      glDeleteProgram(program);
      program = 0;
    }
  }
  if (vertex) glDeleteShader(vertex);
  if (fragment) glDeleteShader(fragment);
  return program;
}

static const char *kReconstructVertex =
    "#version 300 es\n"
    "layout(location = 0) in vec2 aPosition;\n"
    "layout(location = 1) in vec2 aUv;\n"
    "out vec2 uv;\n"
    "void main() { uv = aUv; gl_Position = vec4(aPosition, 0.0, 1.0); }\n";

// Replaces every `from` in text (each must occur); returns a new string.
static char *Replace(char *text, const char *from, const char *to) {
  if (!text) return NULL;
  size_t from_length = strlen(from), to_length = strlen(to), count = 0;
  for (const char *at = strstr(text, from); at; at = strstr(at + from_length, from)) count++;
  if (!count) {
    LOGE("Reconstruct shader: \"%s\" not found; upstream changed", from);
    free(text);
    return NULL;
  }
  char *out = malloc(strlen(text) + count * to_length + 1);
  char *write = out;
  const char *read = text;
  for (const char *at = strstr(read, from); at; at = strstr(read, from)) {
    memcpy(write, read, (size_t)(at - read));
    write += at - read;
    memcpy(write, to, to_length);
    write += to_length;
    read = at + from_length;
  }
  strcpy(write, read);
  free(text);
  return out;
}

// GLSL 1.20 to GLSL ES 3. The frame is BGRA, so the texel fetch swaps it back.
static char *ReconstructSource(void) {
  char *text = strdup(kReconstructFragmentSource);
  text = Replace(text, "#version 120\n",
                 "#version 300 es\nprecision highp float;\nprecision highp int;\nout vec4 fragColor;\n");
  text = Replace(text, "varying vec2 uv;", "in vec2 uv;");
  text = Replace(text, "texture2D(source, (t + 0.5) / source_size).rgb",
                 "texture(source, (t + 0.5) / source_size).bgr");
  text = Replace(text, "gl_FragColor", "fragColor");
  return text;
}

bool RenderInit(void) {
  s_program = Link(kVertex, kFragment);
  if (!s_program) return false;
  // Optional: without it the filter falls back to sharp bilinear.
  char *hd = ReconstructSource();
  s_hd_program = hd ? Link(kReconstructVertex, hd) : 0;
  free(hd);
  if (s_hd_program) {
    s_hd_source = glGetUniformLocation(s_hd_program, "source");
    s_hd_size = glGetUniformLocation(s_hd_program, "source_size");
    s_hd_output = glGetUniformLocation(s_hd_program, "output_size");
    s_hd_mode = glGetUniformLocation(s_hd_program, "mode");
    s_hd_strength = glGetUniformLocation(s_hd_program, "strength");
    s_hd_softness = glGetUniformLocation(s_hd_program, "softness");
    s_hd_shading = glGetUniformLocation(s_hd_program, "shading");
  }
  // Optional too: a filter that does not build falls back to sharp bilinear.
  const char *const extra[] = {kXbrFragment, kCrtLottesFragment, kCrtHyllianFragment};
  for (int i = 0; i <= kFilterCrtHyllian - kFilterXbr; i++) {
    ExtraFilter *filter = &s_extra[i];
    filter->program = Link(kVertex, extra[i]);
    if (!filter->program) continue;
    filter->texture = glGetUniformLocation(filter->program, "uTexture");
    filter->source = glGetUniformLocation(filter->program, "uSource");
    filter->output = glGetUniformLocation(filter->program, "uOutput");
  }
  UpscaleInit();
  s_u_texture = glGetUniformLocation(s_program, "uTexture");
  s_u_source = glGetUniformLocation(s_program, "uSource");
  s_u_output = glGetUniformLocation(s_program, "uOutput");
  s_u_filter = glGetUniformLocation(s_program, "uFilter");
  s_u_effect = glGetUniformLocation(s_program, "uEffect");

  glGenVertexArrays(1, &s_vao);
  glGenBuffers(1, &s_vbo);
  glBindVertexArray(s_vao);
  glBindBuffer(GL_ARRAY_BUFFER, s_vbo);
  glBufferData(GL_ARRAY_BUFFER, 16 * sizeof(float), NULL, GL_DYNAMIC_DRAW);
  glEnableVertexAttribArray(0);
  glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void *)0);
  glEnableVertexAttribArray(1);
  glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                        (void *)(2 * sizeof(float)));
  glBindVertexArray(0);

  glGenTextures(1, &s_texture);
  s_tex_width = s_tex_height = 0;
  s_have_frame = false;
  return true;
}

void RenderShutdown(void) {
  if (s_texture) glDeleteTextures(1, &s_texture);
  if (s_vbo) glDeleteBuffers(1, &s_vbo);
  if (s_vao) glDeleteVertexArrays(1, &s_vao);
  if (s_program) glDeleteProgram(s_program);
  if (s_hd_program) glDeleteProgram(s_hd_program);
  UpscaleShutdown();
  for (int i = 0; i <= kFilterCrtHyllian - kFilterXbr; i++) {
    if (s_extra[i].program) glDeleteProgram(s_extra[i].program);
    s_extra[i].program = 0;
  }
  s_texture = s_vbo = s_vao = s_program = s_hd_program = 0;
}

void RenderUpload(const uint8_t *pixels, int width, int height) {
  if (!s_texture || !pixels || width <= 0 || height <= 0) return;
  glBindTexture(GL_TEXTURE_2D, s_texture);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
  // Exactly the frame's size, so linear filtering never reads past its edge.
  if (width != s_tex_width || height != s_tex_height) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, pixels);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    s_tex_width = width;
    s_tex_height = height;
  } else {
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA,
                    GL_UNSIGNED_BYTE, pixels);
  }
  s_frame_width = width;
  s_frame_height = height;
  s_have_frame = true;
}

void RenderDraw(int drawable_width, int drawable_height, int filter, int effect,
                int scale, bool original_pixels, int hd_level) {
  glViewport(0, 0, drawable_width, drawable_height);
  glClearColor(0, 0, 0, 1);
  glClear(GL_COLOR_BUFFER_BIT);
  if (!s_have_frame || drawable_width <= 0 || drawable_height <= 0) return;

  // The console drew 8:7 pixels: 256 columns filled a 4:3 picture.
  float pixel_aspect = original_pixels ? 8.0f / 7.0f : 1.0f;
  float content_width = s_frame_width * pixel_aspect;
  float content_height = (float)s_frame_height;
  float width, height;
  if (scale == kScaleStretch) {
    width = (float)drawable_width;
    height = (float)drawable_height;
  } else {
    float fit = fminf(drawable_width / content_width, drawable_height / content_height);
    if (scale == kScaleInteger && fit >= 1.0f) fit = floorf(fit);
    width = content_width * fit;
    height = content_height * fit;
  }
  float x = width / drawable_width;  // half extents in clip space
  float y = height / drawable_height;
  const float quad[16] = {
      -x, -y, 0.0f, 1.0f,  x, -y, 1.0f, 1.0f,
      -x,  y, 0.0f, 0.0f,  x,  y, 1.0f, 0.0f,
  };
  glBindVertexArray(s_vao);
  glBindBuffer(GL_ARRAY_BUFFER, s_vbo);
  glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof quad, quad);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, s_texture);
  if (filter == kFilterReconstruct && s_hd_program) {
    // The upstream defaults are the middle level; it fetches texels itself.
    static const int kModes[] = {1, 3, 4};
    static const float kShading[] = {0.3f, 0.6f, 0.6f};
    int level = hd_level < 0 ? 0 : hd_level > 2 ? 2 : hd_level;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glUseProgram(s_hd_program);
    glUniform1i(s_hd_source, 0);
    glUniform2f(s_hd_size, (float)s_frame_width, (float)s_frame_height);
    glUniform2f(s_hd_output, width, height);
    glUniform1i(s_hd_mode, kModes[level]);
    glUniform1f(s_hd_strength, 1.0f);
    glUniform1f(s_hd_softness, 0.5f);
    glUniform1f(s_hd_shading, kShading[level]);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    return;
  }
  if (filter >= kFilterFsr &&
      UpscaleDraw(filter - kFilterFsr, s_texture, s_frame_width, s_frame_height,
                  (int)lroundf(width), (int)lroundf(height), s_vao, drawable_width, drawable_height))
    return;
  if (filter >= kFilterXbr && filter <= kFilterCrtHyllian && s_extra[filter - kFilterXbr].program) {
    // These pick their own texels, and the CRT ones replace the effects.
    const ExtraFilter *extra = &s_extra[filter - kFilterXbr];
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glUseProgram(extra->program);
    glUniform1i(extra->texture, 0);
    glUniform2f(extra->source, (float)s_frame_width, (float)s_frame_height);
    glUniform2f(extra->output, width, height);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    return;
  }
  if (filter >= kFilterReconstruct) filter = kFilterSharpLinear;
  glUseProgram(s_program);
  GLint sampling = filter == kFilterNearest ? GL_NEAREST : GL_LINEAR;
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, sampling);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, sampling);
  glUniform1i(s_u_texture, 0);
  glUniform2f(s_u_source, (float)s_frame_width, (float)s_frame_height);
  glUniform2f(s_u_output, width, height);
  glUniform1i(s_u_filter, filter);
  glUniform1i(s_u_effect, effect);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  glBindVertexArray(0);
}
