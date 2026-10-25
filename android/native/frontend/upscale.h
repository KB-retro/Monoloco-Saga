// Multi-pass upscalers: AMD FSR 1 (EASU + RCAS) and the Anime4K networks
// followed by FSR. They draw into textures first, then onto the screen.
#pragma once

#include <GLES3/gl3.h>
#include <stdbool.h>

enum {
  kUpscaleFsr = 0,
  kUpscaleAnime4kLight = 1,  // Anime4K's 3D graphics network
  kUpscaleAnime4kFull = 2,   // Anime4K's CNN M
  kUpscaleCount = 3,
};

// One pass of an mpv user shader (anime4k_shaders.h): it reads `binds`, writes
// `save` and is as large as `size_of` times `scale`.
typedef struct {
  const char *source;
  const char *binds[8];
  int bind_count;
  const char *save;
  const char *size_of;
  int scale;
} HookPass;

void UpscaleInit(void);
void UpscaleShutdown(void);
// Draws the frame (frame_width x frame_height, BGRA) as a picture of
// picture_width x picture_height with `picture_vao`, the quad that places it
// on the drawable; `restore` first cleans it with Anime4K Restore. Returns
// false when the method cannot run on this GPU.
bool UpscaleDraw(int method, bool restore, GLuint frame, int frame_width, int frame_height,
                 int picture_width, int picture_height, GLuint picture_vao,
                 int drawable_width, int drawable_height);
