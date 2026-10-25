// The MonolocoCore table, shared by the three cores. The game specifics come
// from gameN.c (core_game.h); the rest is the snesrecomp runtime API, which
// the three pinned revisions have in common.
#include "core_game.h"
#include "monoloco_core.h"

#include "common_cpu_infra.h"
#include "common_rtl.h"

#include <android/log.h>
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG "MonolocoCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static uint8_t s_pixels[kMonolocoVideoMaxWidth * kMonolocoVideoHeight * 4];
static uint8_t *s_rom;
static uint8_t *s_sram_copy;
static int s_pending_aspect = -1;
static unsigned s_frames;

static void SetError(char *error, size_t size, const char *message) {
  if (error && size) snprintf(error, size, "%s", message);
}

// The runtime ends the process on a fatal error (Die). Leave the reason where
// the launcher can show it next time; the working directory is save_dir.
void __real_Die(const char *error) __attribute__((noreturn));
void __wrap_Die(const char *error) __attribute__((noreturn));
void __wrap_Die(const char *error) {
  LOGE("fatal: %s", error ? error : "unknown error");
  FILE *file = fopen("last_error.txt", "w");
  if (file) {
    fprintf(file, "%s\n", error ? error : "unknown error");
    fclose(file);
  }
  __real_Die(error);
}

// A ROM hack (the launcher patched the verified ROM, or found one patched):
// any payload of the retail size. The native code still comes from the retail
// ROM, so only hacks that change data (levels, graphics, text) play right.
static uint8_t *ReadModifiedRom(const char *path, size_t *size, char *error, size_t error_size) {
  enum { kRomSize = 0x400000, kHeader = 512 };
  FILE *file = fopen(path, "rb");
  if (!file) {
    SetError(error, error_size, "unable to open the modified ROM");
    return NULL;
  }
  uint8_t *rom = malloc(kRomSize + kHeader);
  size_t length = rom ? fread(rom, 1, kRomSize + kHeader, file) : 0;
  bool longer = rom && fgetc(file) != EOF;
  fclose(file);
  if (!rom) {
    SetError(error, error_size, "not enough memory to load the ROM");
    return NULL;
  }
  if (longer || (length != kRomSize && length != kRomSize + kHeader)) {
    SetError(error, error_size, "the modified ROM is not 4 MB; expanded hacks are not supported");
    free(rom);
    return NULL;
  }
  if (length == kRomSize + kHeader) memmove(rom, rom + kHeader, kRomSize);
  *size = kRomSize;
  return rom;
}

static void SnapshotSram(void) {
  if (!g_sram || g_sram_size <= 0) return;
  if (!s_sram_copy) s_sram_copy = malloc((size_t)g_sram_size);
  if (s_sram_copy) memcpy(s_sram_copy, g_sram, (size_t)g_sram_size);
}

static int CoreLoad(const char *rom_path, const char *save_dir, int aspect,
                    int audio_rate, char *error, size_t error_size) {
  if (s_rom) {
    SetError(error, error_size, "core already loaded");
    return 0;
  }
  if (!save_dir || (mkdir(save_dir, 0755) != 0 && errno != EEXIST) ||
      chdir(save_dir) != 0) {
    SetError(error, error_size, "cannot open the save folder");
    return 0;
  }
  remove("last_error.txt");
  // Relative, like the desktop hosts: the runtime keeps short path buffers.
  RtlSetSaveRoot("saves");
  size_t size = 0;
  // Set by the frontend for a ROM hack (see ReadModifiedRom).
  const char *modified = getenv("MONOLOCO_MODIFIED_ROM");
  s_rom = modified && *modified ? ReadModifiedRom(rom_path, &size, error, error_size)
                                : GameReadVerifiedRom(rom_path, &size, error, error_size);
  if (!s_rom) return 0;
  if (aspect < 0 || aspect >= kMonolocoAspectCount) aspect = kMonolocoAspectNative;
  if (!GameStart(s_rom, size, aspect)) {
    SetError(error, error_size, "the runtime rejected the ROM");
    free(s_rom);
    s_rom = NULL;
    return 0;
  }
  RtlReadSram();
  SnapshotSram();
  GameBeginDrawing(s_pixels, (size_t)GameWidth() * 4);
  RtlSetAudioOutputRate(audio_rate > 0 ? audio_rate : 48000);
  LOGI("game %d loaded: %zu bytes, width %d", MONOLOCO_GAME, size, GameWidth());
  return 1;
}

static void CoreSetAspect(int aspect) {
  if (aspect >= 0 && aspect < kMonolocoAspectCount) s_pending_aspect = aspect;
}

static void ApplyPendingAspect(void) {
  if (s_pending_aspect < 0) return;
  GameSetAspect(s_pending_aspect);
  s_pending_aspect = -1;
  memset(s_pixels, 0, sizeof s_pixels);
  GameBeginDrawing(s_pixels, (size_t)GameWidth() * 4);
}

static int CoreWidth(void) { return GameWidth(); }

static void CoreFlushSave(int force) {
  if (!s_rom || !g_sram || g_sram_size <= 0) return;
  if (!force && s_sram_copy &&
      memcmp(s_sram_copy, g_sram, (size_t)g_sram_size) == 0)
    return;
  RtlWriteSram();
  SnapshotSram();
}

static int CoreRunFrame(uint32_t input) {
  if (!s_rom) return 0;
  ApplyPendingAspect();
  RtlRunFrame(input);
  if (g_fail || !GameFrameOk()) return 0;
  GameDrawFrame();
  // Battery saves change rarely; checking twice a second costs nothing.
  if (++s_frames % 30 == 0) CoreFlushSave(0);
  return 1;
}

static const uint8_t *CorePixels(void) { return s_pixels; }

static void CoreRenderAudio(int16_t *samples, int frames) {
  if (!s_rom || frames <= 0) return;
  RtlRenderAudio(samples, frames, 2);
}

static int CoreSaveState(const char *path) {
  return s_rom && path && RtlSaveSnapshot(path);
}

static int CoreLoadState(const char *path) {
  if (!s_rom || !path || !RtlLoadSnapshot(path)) return 0;
  GameDrawFrame();
  return 1;
}

static const MonolocoCore kCore = {
    .abi = MONOLOCO_CORE_ABI,
    .game = MONOLOCO_GAME,
    .load = CoreLoad,
    .set_aspect = CoreSetAspect,
    .width = CoreWidth,
    .run_frame = CoreRunFrame,
    .pixels = CorePixels,
    .render_audio = CoreRenderAudio,
    .flush_save = CoreFlushSave,
    .save_state = CoreSaveState,
    .load_state = CoreLoadState,
};

__attribute__((visibility("default"))) const MonolocoCore *monoloco_core(void) {
  return &kCore;
}
