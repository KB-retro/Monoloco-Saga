// libmain.so: SDL_main for GameActivity. Loads the chosen game's core, runs
// it at the console's rate and presents it with OpenGL ES 3. Arguments from
// GameActivity.getArguments(): game number, core library, ROM, save folder.
#include "frontend.h"
#include "monoloco_core.h"
#include "perf.h"
#include "render.h"

#include <SDL.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Monoloco", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Monoloco", __VA_ARGS__)

// NTSC console: 21.477 MHz master clock, 357368 clocks per frame.
static const double kGameHz = 21477272.0 / 357368.0;
enum { kMaxPlayers = 2, kMaxAudioFrames = 2048 };

static const MonolocoCore *s_core;
static SDL_GameController *s_pads[kMaxPlayers];
static SDL_AudioDeviceID s_audio;
static int s_audio_rate = 48000;
static double s_audio_fraction;

bool FrontendRunRequest(int request, const char *path) {
  if (!s_core || atomic_load(&g_frontend.state) != kGameRunning) return false;
  switch (request) {
    case kRequestSaveState:
      s_core->flush_save(0);
      return s_core->save_state(path) != 0;
    case kRequestLoadState:
      if (!s_core->load_state(path)) return false;
      RenderUpload(s_core->pixels(), s_core->width(), kMonolocoVideoHeight);
      return true;
    case kRequestFlush:
      s_core->flush_save(1);
      return true;
    default:
      return false;
  }
}

static void Fail(const char *message) {
  LOGE("%s", message);
  FrontendSetError(message);
  atomic_store(&g_frontend.state, kGameFailed);
}

static const MonolocoCore *LoadCore(const char *path) {
  void *library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!library) {
    LOGE("dlopen %s: %s", path, dlerror());
    return NULL;
  }
  MonolocoCoreEntry entry = (MonolocoCoreEntry)dlsym(library, MONOLOCO_CORE_SYMBOL);
  const MonolocoCore *core = entry ? entry() : NULL;
  if (!core || core->abi != MONOLOCO_CORE_ABI) {
    LOGE("%s is not a compatible core", path);
    return NULL;
  }
  return core;
}

// Controllers in the order they were connected: the first is player 1.
static void OpenPad(int device) {
  if (!SDL_IsGameController(device)) return;
  for (int i = 0; i < kMaxPlayers; i++) {
    if (s_pads[i]) continue;
    s_pads[i] = SDL_GameControllerOpen(device);
    return;
  }
}

static void ClosePad(SDL_JoystickID id) {
  for (int i = 0; i < kMaxPlayers; i++) {
    if (!s_pads[i]) continue;
    SDL_Joystick *joystick = SDL_GameControllerGetJoystick(s_pads[i]);
    if (SDL_JoystickInstanceID(joystick) != id) continue;
    SDL_GameControllerClose(s_pads[i]);
    s_pads[i] = NULL;
  }
}

// The runners' packed bits: B Y Select Start Up Down Left Right A X L R.
// Buttons by position, as on the console pad: bottom B, left Y, right A, top X.
static uint32_t PadBits(SDL_GameController *pad) {
  if (!pad) return 0;
  uint32_t bits = 0;
  const int dead = 16000;
  int x = SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTX);
  int y = SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTY);
#define PRESSED(b) SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_##b)
  if (PRESSED(A)) bits |= 1u << 0;
  if (PRESSED(X)) bits |= 1u << 1;
  if (PRESSED(BACK)) bits |= 1u << 2;
  if (PRESSED(START)) bits |= 1u << 3;
  if (PRESSED(DPAD_UP) || y < -dead) bits |= 1u << 4;
  if (PRESSED(DPAD_DOWN) || y > dead) bits |= 1u << 5;
  if (PRESSED(DPAD_LEFT) || x < -dead) bits |= 1u << 6;
  if (PRESSED(DPAD_RIGHT) || x > dead) bits |= 1u << 7;
  if (PRESSED(B)) bits |= 1u << 8;
  if (PRESSED(Y)) bits |= 1u << 9;
  if (PRESSED(LEFTSHOULDER)) bits |= 1u << 10;
  if (PRESSED(RIGHTSHOULDER)) bits |= 1u << 11;
#undef PRESSED
  // Up and down (or left and right) together confuse the games.
  if ((bits & 0x30) == 0x30) bits &= ~0x30u;
  if ((bits & 0xC0) == 0xC0) bits &= ~0xC0u;
  return bits;
}

static uint32_t ReadInput(void) {
  uint32_t player1 = PadBits(s_pads[0]) | atomic_load(&g_frontend.touch_buttons);
  uint32_t player2 = PadBits(s_pads[1]);
  return (player1 & 0xFFFu) | ((player2 & 0xFFFu) << 12);
}

static void OpenAudio(void) {
  SDL_AudioSpec want, have;
  SDL_zero(want);
  want.freq = 48000;
  want.format = AUDIO_S16SYS;
  want.channels = 2;
  want.samples = 1024;
  s_audio = SDL_OpenAudioDevice(NULL, 0, &want, &have, SDL_AUDIO_ALLOW_FREQUENCY_CHANGE);
  if (!s_audio) {
    LOGE("no audio: %s", SDL_GetError());
    return;
  }
  s_audio_rate = have.freq;
  SDL_PauseAudioDevice(s_audio, 0);
}

// One frame of sound. Past ~120 ms of backlog the frame's sound is dropped, so
// a hitch never turns into permanent lag.
static void QueueAudio(void) {
  static int16_t samples[kMaxAudioFrames * 2];
  s_audio_fraction += s_audio_rate / kGameHz;
  int frames = (int)s_audio_fraction;
  s_audio_fraction -= frames;
  if (frames > kMaxAudioFrames) frames = kMaxAudioFrames;
  s_core->render_audio(samples, frames);
  if (!s_audio) return;
  Uint32 queued = SDL_GetQueuedAudioSize(s_audio);
  if (queued > (Uint32)(s_audio_rate * 4 * 120 / 1000)) return;
  int volume = atomic_load(&g_frontend.volume);
  if (volume < 100) {
    for (int i = 0; i < frames * 2; i++) samples[i] = (int16_t)(samples[i] * volume / 100);
  }
  SDL_QueueAudio(s_audio, samples, (Uint32)frames * 4);
}

static void SetAudioPaused(bool paused) {
  if (!s_audio) return;
  SDL_PauseAudioDevice(s_audio, paused ? 1 : 0);
  if (paused) SDL_ClearQueuedAudio(s_audio);
}

int main(int argc, char *argv[]) {
  if (argc < 5) {
    Fail("missing game arguments");
    return 1;
  }
  int game = atoi(argv[1]);
  const char *core_path = argv[2], *rom_path = argv[3], *save_dir = argv[4];
  LOGI("game %d core %s", game, core_path);

  SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "0");
  SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
  if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_GAMECONTROLLER) != 0) {
    Fail(SDL_GetError());
    return 1;
  }
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
  SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
  SDL_Window *window = SDL_CreateWindow("Monoloco Saga", 0, 0, 0, 0,
      SDL_WINDOW_OPENGL | SDL_WINDOW_FULLSCREEN | SDL_WINDOW_SHOWN);
  SDL_GLContext context = window ? SDL_GL_CreateContext(window) : NULL;
  if (!context || !RenderInit()) {
    Fail(window ? "OpenGL ES 3 is not available" : SDL_GetError());
    return 1;
  }
  SDL_GL_SetSwapInterval(1);
  OpenAudio();

  char error[256] = {0};
  s_core = LoadCore(core_path);
  if (!s_core || s_core->game != game) {
    Fail("core");
  } else if (!s_core->load(rom_path, save_dir, atomic_load(&g_frontend.aspect),
                           s_audio_rate, error, sizeof error)) {
    Fail(error[0] ? error : "load");
  } else {
    atomic_store(&g_frontend.state, kGameRunning);
  }
  int aspect_generation = atomic_load(&g_frontend.aspect_generation);
  PerfStart((int64_t)(1e9 / kGameHz));
  PerfCheckCores();

  Uint64 frequency = SDL_GetPerformanceFrequency();
  Uint64 previous = SDL_GetPerformanceCounter();
  double pending = 0;
  bool was_paused = false;
  bool running = true;
  double frame_seconds = 0;
  unsigned core_checks = 0;
  while (running) {
    SDL_Event event;
    while (SDL_PollEvent(&event)) {
      switch (event.type) {
        case SDL_QUIT:
          running = false;
          break;
        case SDL_APP_WILLENTERBACKGROUND:
          if (s_core && atomic_load(&g_frontend.state) == kGameRunning) s_core->flush_save(0);
          break;
        case SDL_CONTROLLERDEVICEADDED:
          OpenPad(event.cdevice.which);
          break;
        case SDL_CONTROLLERDEVICEREMOVED:
          ClosePad(event.cdevice.which);
          break;
        default:
          break;
      }
    }
    FrontendServiceRequests();
    bool live = atomic_load(&g_frontend.state) == kGameRunning;
    int generation = atomic_load(&g_frontend.aspect_generation);
    if (live && generation != aspect_generation) {
      aspect_generation = generation;
      s_core->set_aspect(atomic_load(&g_frontend.aspect));
    }

    Uint64 now = SDL_GetPerformanceCounter();
    double elapsed = (double)(now - previous) / (double)frequency;
    previous = now;
    bool paused = atomic_load(&g_frontend.menu_open) || !live;
    if (paused != was_paused) {
      SetAudioPaused(paused);
      was_paused = paused;
      pending = 0;
    }
    if (!paused) {
      // After a stall (background, a slow frame) catch up at most a little.
      pending += elapsed > 0.1 ? 1.0 / kGameHz : elapsed;
      int steps = 0;
      Uint64 work_start = SDL_GetPerformanceCounter();
      while (pending >= 1.0 / kGameHz && steps < 3) {
        pending -= 1.0 / kGameHz;
        steps++;
        if (!s_core->run_frame(ReadInput())) {
          Fail("The game stopped unexpectedly.");
          break;
        }
        QueueAudio();
      }
      if (steps) {
        RenderUpload(s_core->pixels(), s_core->width(), kMonolocoVideoHeight);
        double work = (double)(SDL_GetPerformanceCounter() - work_start) / (double)frequency;
        PerfReport((int64_t)(work * 1e9));
        // Smoothed CPU time per guest frame: over 16.6 ms the device cannot keep up.
        frame_seconds += (work / steps - frame_seconds) * 0.05;
        atomic_store(&g_frontend.frame_micros, (int)(frame_seconds * 1e6));
        atomic_fetch_add(&g_frontend.game_frames, steps);
        if (++core_checks % 30 == 0) PerfCheckCores();
      }
      if (pending > 3.0 / kGameHz) pending = 0;
    }

    int width = 0, height = 0;
    SDL_GL_GetDrawableSize(window, &width, &height);
    RenderDraw(width, height, atomic_load(&g_frontend.filter), atomic_load(&g_frontend.effect),
               atomic_load(&g_frontend.scale), atomic_load(&g_frontend.original_pixels),
               atomic_load(&g_frontend.hd_level), atomic_load(&g_frontend.restore));
    SDL_GL_SwapWindow(window);
    atomic_fetch_add(&g_frontend.presented_frames, 1);
    if (paused) SDL_Delay(8);  // the menu is up: no need to spin
  }

  if (s_core && atomic_load(&g_frontend.state) == kGameRunning) s_core->flush_save(0);
  RenderShutdown();
  if (s_audio) SDL_CloseAudioDevice(s_audio);
  SDL_GL_DeleteContext(context);
  SDL_DestroyWindow(window);
  SDL_Quit();
  return 0;
}
