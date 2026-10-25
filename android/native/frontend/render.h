// OpenGL ES 3 presentation of the core's BGRA frame.
#pragma once

#include <stdbool.h>
#include <stdint.h>

bool RenderInit(void);
void RenderShutdown(void);
// Uploads a new frame (width x 224 BGRA).
void RenderUpload(const uint8_t *pixels, int width, int height);
// Draws the last uploaded frame into the drawable of the given size.
void RenderDraw(int drawable_width, int drawable_height, int filter, int effect,
                int scale, bool original_pixels, int hd_level, bool restore);
