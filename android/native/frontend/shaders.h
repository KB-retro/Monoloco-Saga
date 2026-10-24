// Fragment shaders ported from libretro's glsl-shaders to GLSL ES 3. Each one
// reads uTexture (the core's BGRA frame), uSource (its size) and uOutput (the
// picture's size on screen), with nearest sampling.
#pragma once

extern const char *const kXbrFragment;         // Hyllian's xBR-lv2 (MIT)
extern const char *const kCrtHyllianFragment;  // Hyllian's CRT (MIT)
