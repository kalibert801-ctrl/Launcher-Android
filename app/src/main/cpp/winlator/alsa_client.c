#include <aaudio/AAudio.h>
#include <jni.h>
#include <stdio.h>

#define WAIT_COMPLETION_TIMEOUT 100 * 1000000L

enum Format {U8, S16LE, S16BE, FLOATLE, FLOATBE};

static aaudio_format_t toAAudioFormat(int format) {
    switch (format) {
        case FLOATLE:
        case FLOATBE:
            return AAUDIO_FORMAT_PCM_FLOAT;
        case U8:
            return AAUDIO_FORMAT_UNSPECIFIED;
        case S16LE:
        case S16BE:
        default:
            return AAUDIO_FORMAT_PCM_I16;
    }
}

static AAudioStream *aaudioCreate(int32_t format, int8_t channelCount, int32_t sampleRate, int32_t bufferSize) {
    aaudio_result_t result;
    AAudioStreamBuilder *builder;
    AAudioStream *stream;

    result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return NULL;

    // AleksOS: режим «низкая задержка» (мелкие порции — Wine не ждёт подолгу; в обычном режиме
    // порция была 250 мс, и Wine считал это заминкой), но запас побольше — ~120 мс,
    // чтобы короткие подвисания игры под Box64 не давали провалов
    int32_t minFrames = sampleRate * 12 / 100;
    if (bufferSize < minFrames) bufferSize = minFrames;
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setBufferCapacityInFrames(builder, bufferSize * 4);
    AAudioStreamBuilder_setFormat(builder, toAAudioFormat(format));
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);

    result = AAudioStreamBuilder_openStream(builder, &stream);
    if (result != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        return NULL;
    }

    // AleksOS: Android часто не даёт быстрый режим (44100 Гц ≠ 48000 у телефона) и берёт звук
    // крупными порциями; запас в одну порцию = провал при любой заминке. Берём весь возможный запас.
    {
        int32_t burst = AAudioStream_getFramesPerBurst(stream);
        int32_t want = bufferSize;
        if (burst > 0 && want < burst * 3) want = burst * 3;
        AAudioStream_setBufferSizeInFrames(stream, want);
    }

    result = AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) return NULL;

    return stream;
}

/* AleksOS: дописываем все кадры, а не сколько успелось за 100 мс, — иначе куски звука терялись (треск) */
static int aaudioWrite(AAudioStream *aaudioStream, void *buffer, int numFrames) {
    aaudio_format_t format = AAudioStream_getFormat(aaudioStream);
    int frameBytes = AAudioStream_getChannelCount(aaudioStream) * (format == AAUDIO_FORMAT_PCM_I16 ? 2 : 4);
    char *data = (char*)buffer;
    int total = 0;
    for (int tries = 0; total < numFrames && tries < 20; tries++) {
        aaudio_result_t n = AAudioStream_write(aaudioStream, data + (long)total * frameBytes, numFrames - total, WAIT_COMPLETION_TIMEOUT);
        if (n < 0) return total > 0 ? total : n;
        total += n;
    }
    return total;
}

static void aaudioStart(AAudioStream *aaudioStream) {
    AAudioStream_requestStart(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_STARTING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioStop(AAudioStream *aaudioStream) {
    AAudioStream_requestStop(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_STOPPING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioPause(AAudioStream *aaudioStream) {
    AAudioStream_requestPause(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_PAUSING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioFlush(AAudioStream *aaudioStream) {
    AAudioStream_requestFlush(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_FLUSHING, NULL, WAIT_COMPLETION_TIMEOUT);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_alsaserver_ALSAClient_create(JNIEnv *env, jobject obj, jint format,
                                               jbyte channelCount, jint sampleRate, jint bufferSize) {
    return (jlong)aaudioCreate(format, channelCount, sampleRate, bufferSize);
}

JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_write(JNIEnv *env, jobject obj, jlong streamPtr, jobject buffer,
                                              jint numFrames) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    if (aaudioStream) {
        return aaudioWrite(aaudioStream, (*env)->GetDirectBufferAddress(env, buffer), numFrames);
    }
    else return -1;
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_start(JNIEnv *env, jobject obj, jlong streamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    if (aaudioStream) aaudioStart(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_stop(JNIEnv *env, jobject obj, jlong streamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    if (aaudioStream) aaudioStop(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_pause(JNIEnv *env, jobject obj, jlong streamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    if (aaudioStream) aaudioPause(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_flush(JNIEnv *env, jobject obj, jlong streamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    if (aaudioStream) aaudioFlush(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_close(JNIEnv *env, jobject obj, jlong streamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    if (aaudioStream) AAudioStream_close(aaudioStream);
}
JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_getXRuns(JNIEnv *env, jobject obj, jlong streamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    return aaudioStream ? AAudioStream_getXRunCount(aaudioStream) : -1;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_alsaserver_ALSAClient_getBufferInfo(JNIEnv *env, jobject obj, jlong streamPtr) {
    AAudioStream *s = (AAudioStream*)streamPtr;
    char text[160];
    if (!s) return (*env)->NewStringUTF(env, "-");
    snprintf(text, sizeof(text), "%d Гц, буфер %d из %d кадров, порция %d, режим %d",
             AAudioStream_getSampleRate(s), AAudioStream_getBufferSizeInFrames(s),
             AAudioStream_getBufferCapacityInFrames(s), AAudioStream_getFramesPerBurst(s),
             AAudioStream_getPerformanceMode(s));
    return (*env)->NewStringUTF(env, text);
}
