#include <jni.h>

#include <string>
#include <vector>

#include "colin_engine.h"

static std::string to_std(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_ai_colin_app_LocalEngine_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new colin::Engine());
}

JNIEXPORT jstring JNICALL Java_ai_colin_app_LocalEngine_nativeLoad(JNIEnv* env, jclass, jlong h, jstring path, jint n_ctx, jint threads) {
    auto* e = reinterpret_cast<colin::Engine*>(h);
    return env->NewStringUTF(e->load(to_std(env, path), n_ctx, threads).c_str());
}

JNIEXPORT jint JNICALL Java_ai_colin_app_LocalEngine_nativeGenerate(
    JNIEnv* env, jclass, jlong h, jobjectArray roles, jobjectArray contents, jint max_tokens, jfloat temp, jobject cb) {
    auto* e = reinterpret_cast<colin::Engine*>(h);
    std::vector<colin::Message> chat;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring)env->GetObjectArrayElement(roles, i);
        auto c = (jstring)env->GetObjectArrayElement(contents, i);
        chat.push_back({to_std(env, r), to_std(env, c)});
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    jclass cls = env->GetObjectClass(cb);
    jmethodID on_text = env->GetMethodID(cls, "onText", "([B)Z");
    return e->generate(chat, max_tokens, temp, [&](const std::string& text) {
        jbyteArray bytes = env->NewByteArray((jsize)text.size());
        env->SetByteArrayRegion(bytes, 0, (jsize)text.size(), reinterpret_cast<const jbyte*>(text.data()));
        jboolean keep = env->CallBooleanMethod(cb, on_text, bytes);
        env->DeleteLocalRef(bytes);
        return keep == JNI_TRUE;
    });
}

JNIEXPORT jstring JNICALL Java_ai_colin_app_LocalEngine_nativeLastError(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(reinterpret_cast<colin::Engine*>(h)->last_error().c_str());
}

JNIEXPORT void JNICALL Java_ai_colin_app_LocalEngine_nativeStop(JNIEnv*, jclass, jlong h) {
    reinterpret_cast<colin::Engine*>(h)->stop();
}

JNIEXPORT void JNICALL Java_ai_colin_app_LocalEngine_nativeFree(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<colin::Engine*>(h);
}

}  // extern "C"
