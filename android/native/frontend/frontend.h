// State shared by the game thread (main.c) and the Java bridge (bridge.c).
#pragma once

#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>

enum {
  kFilterNearest = 0,
  kFilterLinear = 1,
  kFilterSharpLinear = 2,
  kFilterReconstruct = 3,  // the second game's upscaler for pre-rendered pixel art
  kFilterXbr = 4,          // Hyllian's xBR: smooth diagonals, sharp edges
  kFilterFsr = 5,          // AMD FSR 1, then the Anime4K networks followed by FSR
  kFilterAnime4kM = 6,
  kFilterAnime4k3d = 7,
  kFilterAnime4kGan = 8,
  kFilterCount = 9,
};

enum {
  kEffectNone = 0,
  kEffectScanlines = 1,
  kEffectCrt = 2,
  kEffectCrtHyllian = 3,  // Hyllian's CRT shader (shaders.c)
  kEffectCount = 4,
};

enum {
  kScaleFit = 0,      // largest picture that keeps the proportions
  kScaleInteger = 1,  // whole multiples of the original lines
  kScaleStretch = 2,  // whole screen
  kScaleCount = 3,
};

enum {
  kGameLoading = 0,
  kGameRunning = 1,
  kGameFailed = 2,
};

// Requests that must run on the game thread, between frames.
enum {
  kRequestNone = 0,
  kRequestSaveState = 1,
  kRequestLoadState = 2,
  kRequestFlush = 3,
};

typedef struct FrontendShared {
  _Atomic bool menu_open;
  _Atomic uint32_t touch_buttons;
  _Atomic int presented_frames;
  _Atomic int filter;
  _Atomic int effect;
  _Atomic int hd_level;          // Reconstruct: 0 low, 1 medium, 2 high
  _Atomic int scale;
  _Atomic bool original_pixels;  // the console's 8:7 pixels, else square
  _Atomic int aspect;            // kMonolocoAspect*
  _Atomic int aspect_generation;
  _Atomic int volume;            // percent
  _Atomic int state;             // kGame*
  _Atomic bool fast_cores;       // every thread on the fastest CPU cluster
  _Atomic int game_frames;       // guest frames run, for the FPS counter
  _Atomic int frame_micros;      // recent average CPU time of one guest frame
} FrontendShared;

extern FrontendShared g_frontend;

// Blocks the calling (Java) thread until the game thread ran the request.
// Returns its result, or false after timeout_ms.
bool FrontendRequest(int request, const char *path, int timeout_ms);
// Game thread: runs a pending request, if any.
void FrontendServiceRequests(void);
// Implemented by main.c for FrontendServiceRequests.
bool FrontendRunRequest(int request, const char *path);
// Last error shown to the player, empty when none.
const char *FrontendError(void);
void FrontendSetError(const char *message);
