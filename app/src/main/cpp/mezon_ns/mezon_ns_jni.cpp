#include <jni.h>
#include <cstdint>
#include <exception>
#include <new>
#include "mezon_ns.h"
#include "mezon_ns_48k.hpp"

extern "C" {

JNIEXPORT jlong JNICALL Java_ai_mezon_ns_MezonNS_nativeCreateFromMemory(
    JNIEnv* env, jclass, jbyteArray model, jfloat attenuation_limit_db) {
    if (!model) return 0;
    const jsize size = env->GetArrayLength(model);
    if (size <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(model, nullptr);
    if (!bytes) return 0;
    MezonNSConfig config;
    mezon_ns_config_init(&config);
    config.num_threads = 1;
    config.attenuation_limit_db = attenuation_limit_db;
    MezonNs48k* engine = nullptr;
    try {
        MezonNSEngine* core = mezon_ns_create_from_memory(bytes, static_cast<size_t>(size), &config);
        if (core) {
            engine = new (std::nothrow) MezonNs48k(core);
            if (!engine) mezon_ns_destroy(core);
        }
    } catch (const std::exception&) {
        engine = nullptr;
    }
    env->ReleaseByteArrayElements(model, bytes, JNI_ABORT);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jint JNICALL Java_ai_mezon_ns_MezonNS_nativeProcessDirect(
    JNIEnv* env, jclass, jlong handle, jobject buffer, jint bytes) {
    auto* engine = reinterpret_cast<MezonNs48k*>(handle);
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!engine || !data || bytes <= 0 || bytes % 960 != 0 || capacity < bytes) return -1;
    try {
        for (int offset = 0; offset < bytes; offset += 960) {
            auto* frame = reinterpret_cast<int16_t*>(data + offset);
            const int result = engine->process(frame);
            if (result != 0) return result;
        }
    } catch (const std::exception&) {
        return -2;
    }
    return 0;
}

JNIEXPORT void JNICALL Java_ai_mezon_ns_MezonNS_nativeSetNoiseGate(
    JNIEnv*, jclass, jlong handle, jboolean enabled) {
    auto* engine = reinterpret_cast<MezonNs48k*>(handle);
    if (engine) mezon_ns_set_noise_gate(engine->core(), enabled ? 1 : 0);
}

JNIEXPORT void JNICALL Java_ai_mezon_ns_MezonNS_nativeSetSuppressionIntensity(
    JNIEnv*, jclass, jlong handle, jfloat gamma) {
    auto* engine = reinterpret_cast<MezonNs48k*>(handle);
    if (engine) mezon_ns_set_suppression_intensity(engine->core(), gamma);
}

JNIEXPORT void JNICALL Java_ai_mezon_ns_MezonNS_nativeSetModelInputTargetDbfs(
    JNIEnv*, jclass, jlong handle, jfloat target_dbfs) {
    auto* engine = reinterpret_cast<MezonNs48k*>(handle);
    if (engine) mezon_ns_set_model_input_target_dbfs(engine->core(), target_dbfs);
}

JNIEXPORT void JNICALL Java_ai_mezon_ns_MezonNS_nativeReset(JNIEnv*, jclass, jlong handle) {
    auto* engine = reinterpret_cast<MezonNs48k*>(handle);
    if (engine) engine->reset();
}

JNIEXPORT void JNICALL Java_ai_mezon_ns_MezonNS_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<MezonNs48k*>(handle);
}

}
