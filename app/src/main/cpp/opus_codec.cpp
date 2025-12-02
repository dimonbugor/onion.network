#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <new>

extern "C" {
#include "opus.h"
}

#define TAG "OpusCodec"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct OpusEncoderWrapper {
    OpusEncoder *encoder = nullptr;
    int frameSize = 0;
    int channels = 0;
};

struct OpusDecoderWrapper {
    OpusDecoder *decoder = nullptr;
    int frameSize = 0;
    int channels = 0;
};

extern "C" JNIEXPORT jlong JNICALL
Java_onion_network_call_codec_OpusCodec_nativeCreateEncoder(JNIEnv *env, jclass, jint sampleRate,
                                                            jint channels, jint bitrate) {
    int error = OPUS_OK;
    auto *wrapper = new(std::nothrow) OpusEncoderWrapper();
    if (!wrapper) return 0;
    wrapper->channels = channels;
    wrapper->frameSize = sampleRate / 1000 * 20; // 20 ms frame
    wrapper->encoder = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &error);
    if (error != OPUS_OK || wrapper->encoder == nullptr) {
        LOGE("opus_encoder_create failed: %d", error);
        delete wrapper;
        return 0;
    }
    opus_encoder_ctl(wrapper->encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(wrapper->encoder, OPUS_SET_VBR(1));
    opus_encoder_ctl(wrapper->encoder, OPUS_SET_COMPLEXITY(5));
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_onion_network_call_codec_OpusCodec_nativeEncode(JNIEnv *env, jclass, jlong handle,
                                                     jbyteArray pcmData, jint length) {
    auto *wrapper = reinterpret_cast<OpusEncoderWrapper *>(handle);
    if (wrapper == nullptr || wrapper->encoder == nullptr || pcmData == nullptr) {
        return env->NewByteArray(0);
    }
    jbyte *pcm = env->GetByteArrayElements(pcmData, nullptr);
    if (!pcm) {
        return env->NewByteArray(0);
    }
    int frameSize = length / (2 * wrapper->channels);
    if (frameSize <= 0) {
        env->ReleaseByteArrayElements(pcmData, pcm, JNI_ABORT);
        return env->NewByteArray(0);
    }
    unsigned char encoded[4000];
    int encodedBytes = opus_encode(wrapper->encoder, reinterpret_cast<const opus_int16 *>(pcm),
                                   frameSize, encoded, sizeof(encoded));
    env->ReleaseByteArrayElements(pcmData, pcm, JNI_ABORT);
    if (encodedBytes < 0) {
        LOGE("opus_encode failed: %d", encodedBytes);
        return env->NewByteArray(0);
    }
    jbyteArray result = env->NewByteArray(encodedBytes);
    env->SetByteArrayRegion(result, 0, encodedBytes, reinterpret_cast<const jbyte *>(encoded));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_onion_network_call_codec_OpusCodec_nativeDestroyEncoder(JNIEnv *, jclass, jlong handle) {
    auto *wrapper = reinterpret_cast<OpusEncoderWrapper *>(handle);
    if (!wrapper) return;
    if (wrapper->encoder) opus_encoder_destroy(wrapper->encoder);
    delete wrapper;
}

extern "C" JNIEXPORT jlong JNICALL
Java_onion_network_call_codec_OpusCodec_nativeCreateDecoder(JNIEnv *env, jclass, jint sampleRate,
                                                            jint channels) {
    int error = OPUS_OK;
    auto *wrapper = new(std::nothrow) OpusDecoderWrapper();
    if (!wrapper) return 0;
    wrapper->channels = channels;
    wrapper->frameSize = sampleRate / 1000 * 20;
    wrapper->decoder = opus_decoder_create(sampleRate, channels, &error);
    if (error != OPUS_OK || wrapper->decoder == nullptr) {
        LOGE("opus_decoder_create failed: %d", error);
        delete wrapper;
        return 0;
    }
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_onion_network_call_codec_OpusCodec_nativeDecode(JNIEnv *env, jclass, jlong handle,
                                                     jbyteArray encodedData, jint length) {
    auto *wrapper = reinterpret_cast<OpusDecoderWrapper *>(handle);
    if (wrapper == nullptr || wrapper->decoder == nullptr || encodedData == nullptr) {
        return env->NewByteArray(0);
    }
    jbyte *encoded = env->GetByteArrayElements(encodedData, nullptr);
    if (!encoded) {
        return env->NewByteArray(0);
    }
    opus_int16 pcm[1920 * 2];
    int frameSize = opus_decode(wrapper->decoder, reinterpret_cast<const unsigned char *>(encoded),
                                length, pcm, wrapper->frameSize, 0);
    env->ReleaseByteArrayElements(encodedData, encoded, JNI_ABORT);
    if (frameSize < 0) {
        LOGE("opus_decode failed: %d", frameSize);
        return env->NewByteArray(0);
    }
    int pcmBytes = frameSize * wrapper->channels * sizeof(opus_int16);
    jbyteArray result = env->NewByteArray(pcmBytes);
    env->SetByteArrayRegion(result, 0, pcmBytes, reinterpret_cast<const jbyte *>(pcm));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_onion_network_call_codec_OpusCodec_nativeDestroyDecoder(JNIEnv *, jclass, jlong handle) {
    auto *wrapper = reinterpret_cast<OpusDecoderWrapper *>(handle);
    if (!wrapper) return;
    if (wrapper->decoder) opus_decoder_destroy(wrapper->decoder);
    delete wrapper;
}
