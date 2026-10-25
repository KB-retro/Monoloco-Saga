// JNI side of com.monolocosaga.GameBridge. Java only flips the shared atomics
// or queues a request for the game thread; it never calls into the core.
#include "frontend.h"
#include "monoloco_core.h"

#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

FrontendShared g_frontend = {
    .filter = kFilterSharpLinear,
    .effect = kEffectNone,
    .scale = kScaleFit,
    .original_pixels = true,
    .aspect = kMonolocoAspectNative,
    .volume = 100,
    .fast_cores = true,
    .hd_level = 1,
};

static pthread_mutex_t s_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t s_done = PTHREAD_COND_INITIALIZER;
static int s_request;
static char s_request_path[512];
static unsigned s_request_serial, s_finished_serial;
static bool s_request_result;
static char s_error[256];

bool FrontendRequest(int request, const char *path, int timeout_ms) {
  pthread_mutex_lock(&s_mutex);
  if (s_request != kRequestNone) {
    pthread_mutex_unlock(&s_mutex);
    return false;
  }
  s_request = request;
  snprintf(s_request_path, sizeof s_request_path, "%s", path ? path : "");
  unsigned serial = ++s_request_serial;
  struct timespec deadline;
  clock_gettime(CLOCK_REALTIME, &deadline);
  deadline.tv_sec += timeout_ms / 1000;
  deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
  if (deadline.tv_nsec >= 1000000000L) {
    deadline.tv_sec++;
    deadline.tv_nsec -= 1000000000L;
  }
  while (s_finished_serial != serial) {
    if (pthread_cond_timedwait(&s_done, &s_mutex, &deadline) != 0) break;
  }
  bool result = s_finished_serial == serial && s_request_result;
  if (s_finished_serial != serial) s_request = kRequestNone;  // never ran
  pthread_mutex_unlock(&s_mutex);
  return result;
}

void FrontendServiceRequests(void) {
  pthread_mutex_lock(&s_mutex);
  int request = s_request;
  unsigned serial = s_request_serial;
  char path[sizeof s_request_path];
  memcpy(path, s_request_path, sizeof path);
  pthread_mutex_unlock(&s_mutex);
  if (request == kRequestNone) return;
  bool result = FrontendRunRequest(request, path);
  pthread_mutex_lock(&s_mutex);
  if (s_request_serial == serial) {
    s_request = kRequestNone;
    s_request_result = result;
    s_finished_serial = serial;
    pthread_cond_broadcast(&s_done);
  }
  pthread_mutex_unlock(&s_mutex);
}

const char *FrontendError(void) { return s_error; }

void FrontendSetError(const char *message) {
  pthread_mutex_lock(&s_mutex);
  snprintf(s_error, sizeof s_error, "%s", message ? message : "");
  pthread_mutex_unlock(&s_mutex);
}

static int Clamp(int value, int count) {
  return value < 0 ? 0 : value >= count ? count - 1 : value;
}

#define BRIDGE(name) Java_com_monolocosaga_GameBridge_##name

JNIEXPORT void JNICALL BRIDGE(nativeSetMenuOpen)(JNIEnv *env, jclass cls, jboolean open) {
  (void)env; (void)cls;
  atomic_store(&g_frontend.menu_open, open == JNI_TRUE);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetTouch)(JNIEnv *env, jclass cls, jint buttons) {
  (void)env; (void)cls;
  atomic_store(&g_frontend.touch_buttons, (uint32_t)buttons & 0xFFFu);
}

JNIEXPORT jint JNICALL BRIDGE(nativeFrames)(JNIEnv *env, jclass cls) {
  (void)env; (void)cls;
  return atomic_load(&g_frontend.presented_frames);
}

JNIEXPORT jint JNICALL BRIDGE(nativeGameFrames)(JNIEnv *env, jclass cls) {
  (void)env; (void)cls;
  return atomic_load(&g_frontend.game_frames);
}

JNIEXPORT jint JNICALL BRIDGE(nativeFrameMicros)(JNIEnv *env, jclass cls) {
  (void)env; (void)cls;
  return atomic_load(&g_frontend.frame_micros);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetFastCores)(JNIEnv *env, jclass cls, jboolean on) {
  (void)env; (void)cls;
  atomic_store(&g_frontend.fast_cores, on == JNI_TRUE);
}

JNIEXPORT jint JNICALL BRIDGE(nativeState)(JNIEnv *env, jclass cls) {
  (void)env; (void)cls;
  return atomic_load(&g_frontend.state);
}

JNIEXPORT jstring JNICALL BRIDGE(nativeError)(JNIEnv *env, jclass cls) {
  (void)cls;
  char copy[sizeof s_error];
  pthread_mutex_lock(&s_mutex);
  memcpy(copy, s_error, sizeof copy);
  pthread_mutex_unlock(&s_mutex);
  return (*env)->NewStringUTF(env, copy);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetVideo)(JNIEnv *env, jclass cls, jint filter,
                                               jint effect, jint scale,
                                               jboolean original_pixels) {
  (void)env; (void)cls;
  atomic_store(&g_frontend.filter, Clamp(filter, kFilterCount));
  atomic_store(&g_frontend.effect, Clamp(effect, kEffectCount));
  atomic_store(&g_frontend.scale, Clamp(scale, kScaleCount));
  atomic_store(&g_frontend.original_pixels, original_pixels == JNI_TRUE);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetHdLevel)(JNIEnv *env, jclass cls, jint level) {
  (void)env; (void)cls;
  atomic_store(&g_frontend.hd_level, level < 0 ? 0 : level > 2 ? 2 : level);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetRestore)(JNIEnv *env, jclass cls, jboolean restore) {
  (void)env; (void)cls;
  atomic_store(&g_frontend.restore, restore == JNI_TRUE);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetAspect)(JNIEnv *env, jclass cls, jint aspect) {
  (void)env; (void)cls;
  atomic_store(&g_frontend.aspect, Clamp(aspect, kMonolocoAspectCount));
  atomic_fetch_add(&g_frontend.aspect_generation, 1);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetVolume)(JNIEnv *env, jclass cls, jint percent) {
  (void)env; (void)cls;
  atomic_store(&g_frontend.volume, percent < 0 ? 0 : percent > 100 ? 100 : percent);
}

static jboolean Request(JNIEnv *env, int request, jstring path) {
  const char *chars = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
  bool result = FrontendRequest(request, chars, 5000);
  if (chars) (*env)->ReleaseStringUTFChars(env, path, chars);
  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL BRIDGE(nativeSaveState)(JNIEnv *env, jclass cls, jstring path) {
  (void)cls;
  return Request(env, kRequestSaveState, path);
}

JNIEXPORT jboolean JNICALL BRIDGE(nativeLoadState)(JNIEnv *env, jclass cls, jstring path) {
  (void)cls;
  return Request(env, kRequestLoadState, path);
}

JNIEXPORT jboolean JNICALL BRIDGE(nativeFlush)(JNIEnv *env, jclass cls) {
  (void)cls;
  return Request(env, kRequestFlush, NULL);
}
