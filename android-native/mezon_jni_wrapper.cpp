#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include "mezon_client.h"

#define LOG_TAG "MezonJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global Java VM reference if you need to call back to Java from C threads
static JavaVM *g_vm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

// Helper to convert JNI byte array to C++ vector
std::vector<uint8_t> jbytearray_to_vector(JNIEnv *env, jbyteArray array) {
  int len = env->GetArrayLength(array);
  std::vector<uint8_t> buf(len);
  env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(buf.data()));
  return buf;
}

/**
 * Native implementation of:
 * private native long nativeConnect(String host, int port);
 */
extern "C" JNIEXPORT jlong JNICALL Java_com_mezon_MezonClient_nativeConnect(JNIEnv *env, jobject thiz, jstring host, jint port) {
  const char *native_host = env->GetStringUTFChars(host, nullptr);

  LOGI("Connecting to %s:%d", native_host, port);

  // Setup configuration
  mezon_config_t cfg;
  memset(&cfg, 0, sizeof(cfg));
  cfg.host = native_host;

  // In a real Android app, you'd resolve the IP and fill remote_addr here
  // For this example, we'll assume the mezon_create handles internal socket setup

  mezon_session_t *session = mezon_create(&cfg, 0);  // Using 0 for now_ns as it's TCP

  env->ReleaseStringUTFChars(host, native_host);

  if (!session) {
    LOGE("Failed to create Mezon session");
    return 0;
  }

  return reinterpret_cast<jlong>(session);
}

/**
 * Native implementation of:
 * private native int nativeSend(long sessionPtr, byte[] data);
 */
extern "C" JNIEXPORT jint JNICALL Java_com_mezon_MezonClient_nativeSend(JNIEnv *env, jobject thiz, jlong session_ptr, jbyteArray data) {
  mezon_session_t *session = reinterpret_cast<mezon_session_t *>(session_ptr);
  if (!session) {
    return -1;
  }

  std::vector<uint8_t> buffer = jbytearray_to_vector(env, data);

  // Using stream_id 0 and fin false for raw TCP abridged
  int result = mezon_send(session, 0, buffer.data(), buffer.size(), false);

  return result;
}

/**
 * Native implementation of:
 * private native byte[] nativePoll(long sessionPtr);
 */
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_mezon_MezonClient_nativePoll(JNIEnv *env, jobject thiz, jlong session_ptr) {
  mezon_session_t *session = reinterpret_cast<mezon_session_t *>(session_ptr);
  if (!session) {
    return nullptr;
  }

  // We use mezon_tick to poll the raw socket for data
  // You'll need to modify mezon_tick or create a helper to return the data
  // For now, let's assume we read directly for this JNI example:

  uint8_t temp_buf[4096];
  // In a real implementation, you'd use your mezon_client logic to parse the framing
  // Here we just show how to return bytes to Java

  // Example: call mezon_tick which internally calls your data_cb
  mezon_tick(session, 0);

  // Note: To pass data back properly, the cfg.data_cb should
  // probably trigger a Java callback or fill a thread-safe queue.
  return nullptr;
}

/**
 * Native implementation of:
 * private native void nativeDestroy(long sessionPtr);
 */
extern "C" JNIEXPORT void JNICALL Java_com_mezon_MezonClient_nativeDestroy(JNIEnv *env, jobject thiz, jlong session_ptr) {
  mezon_session_t *session = reinterpret_cast<mezon_session_t *>(session_ptr);
  if (session) {
    LOGI("Destroying Mezon session");
    // Assuming you add a mezon_destroy to your mezon_client.c
    // mezon_destroy(session);
    free(session);
  }
}