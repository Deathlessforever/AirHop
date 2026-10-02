#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <memory>
#include <mutex>
#include "airhop_packet.h"
#include "gf256.h"
#include "reed_solomon.h"
#include "fnv1a.h"
#include "audio_ring_buffer.h"
#include "silero_vad.h"

#define TAG "AirHopNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::unique_ptr<ReedSolomon> g_rs;
static std::unique_ptr<AudioRingBuffer> g_audio_ring;
static std::unique_ptr<SileroVadStateMachine> g_vad;
static std::mutex g_audio_mutex;

static void ensure_initialized() {
    static std::once_flag init_flag;
    std::call_once(init_flag, []() {
        GF256::instance();
        g_rs = std::make_unique<ReedSolomon>();
        g_audio_ring = std::make_unique<AudioRingBuffer>(65536);
        g_vad = std::make_unique<SileroVadStateMachine>();
        LOGI("AirHop Native Core initialized: GF(256), RS(40,32), RingBuffer, Silero VAD");
    });
}

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_team_vocalink_core_AirHopNative_encodePacket(
        JNIEnv* env,
        jobject /* this */,
        jbyte flags,
        jbyte ttl,
        jint target_zone,
        jint lat_e7,
        jint lon_e7,
        jbyteArray tokens_array,
        jlong timestamp_ms) {

    ensure_initialized();

    AirHopPacket packet;
    std::memset(&packet, 0, sizeof(packet));

    packet.preamble = AIRHOP_PREAMBLE;
    packet.flags = static_cast<uint8_t>(flags);
    packet.ttl = static_cast<uint8_t>(ttl);
    packet.target_zone = static_cast<uint32_t>(target_zone);
    packet.lat_e7 = static_cast<int32_t>(lat_e7);
    packet.lon_e7 = static_cast<int32_t>(lon_e7);

    // Copy 13 tokens
    jsize token_len = 0;
    if (tokens_array != nullptr) {
        token_len = env->GetArrayLength(tokens_array);
        jbyte* token_elems = env->GetByteArrayElements(tokens_array, nullptr);
        size_t copy_len = (token_len > 13) ? 13 : static_cast<size_t>(token_len);
        std::memcpy(packet.tokens, token_elems, copy_len);
        env->ReleaseByteArrayElements(tokens_array, token_elems, JNI_ABORT);
    }

    // Compute 32-bit FNV-1a msg_id over lat/lon/timestamp/tokens
    packet.msg_id = compute_airhop_msg_id(
            packet.lat_e7,
            packet.lon_e7,
            static_cast<uint64_t>(timestamp_ms),
            packet.tokens,
            13
    );

    // Compute RS(40,32) parity over the first 32 bytes
    uint8_t* raw_bytes = reinterpret_cast<uint8_t*>(&packet);
    g_rs->encode(raw_bytes, packet.fec_parity);

    // Return 40-byte jbyteArray
    jbyteArray result = env->NewByteArray(PACKET_TOTAL_SIZE);
    env->SetByteArrayRegion(result, 0, PACKET_TOTAL_SIZE, reinterpret_cast<const jbyte*>(raw_bytes));
    return result;
}

JNIEXPORT jobject JNICALL
Java_com_team_vocalink_core_AirHopNative_decodeAndRepairPacket(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray raw_packet_array) {

    ensure_initialized();

    if (raw_packet_array == nullptr || env->GetArrayLength(raw_packet_array) != PACKET_TOTAL_SIZE) {
        LOGE("decodeAndRepairPacket: invalid packet buffer length");
        return nullptr;
    }

    uint8_t packet_buffer[PACKET_TOTAL_SIZE];
    env->GetByteArrayRegion(raw_packet_array, 0, PACKET_TOTAL_SIZE, reinterpret_cast<jbyte*>(packet_buffer));

    // Decode and automatically repair up to 4 corrupted bytes using RS(40,32)
    RsDecodeResult decode_res = g_rs->decode_and_repair(packet_buffer);

    const AirHopPacket* pkt = reinterpret_cast<const AirHopPacket*>(packet_buffer);
    if (decode_res.success && pkt->preamble != AIRHOP_PREAMBLE) {
        LOGW("decodeAndRepairPacket: invalid preamble 0x%02X after RS decode", pkt->preamble);
        decode_res.success = false;
    }

    // Find Kotlin class: com.team.vocalink.core.PacketRepairResult
    jclass cls = env->FindClass("com/team/vocalink/core/PacketRepairResult");
    if (cls == nullptr) {
        LOGE("Failed to find class com/team/vocalink/core/PacketRepairResult");
        return nullptr;
    }

    // Constructor: (ZIZ[BIIIIII[B)V
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ZIZ[BIIIIII[B)V");
    if (ctor == nullptr) {
        LOGE("Failed to find constructor for PacketRepairResult");
        return nullptr;
    }

    jbyteArray repaired_array = nullptr;
    jbyteArray tokens_array = nullptr;

    if (decode_res.success) {
        repaired_array = env->NewByteArray(PACKET_TOTAL_SIZE);
        env->SetByteArrayRegion(repaired_array, 0, PACKET_TOTAL_SIZE, reinterpret_cast<const jbyte*>(packet_buffer));

        tokens_array = env->NewByteArray(13);
        env->SetByteArrayRegion(tokens_array, 0, 13, reinterpret_cast<const jbyte*>(pkt->tokens));
    }

    jobject result_obj = env->NewObject(
            cls,
            ctor,
            static_cast<jboolean>(decode_res.success),
            static_cast<jint>(decode_res.corrected_bytes),
            static_cast<jboolean>(decode_res.had_errors),
            repaired_array,
            decode_res.success ? static_cast<jint>(pkt->msg_id) : 0,
            decode_res.success ? static_cast<jint>(pkt->flags) : 0,
            decode_res.success ? static_cast<jint>(pkt->ttl) : 0,
            decode_res.success ? static_cast<jint>(pkt->target_zone) : 0,
            decode_res.success ? static_cast<jint>(pkt->lat_e7) : 0,
            decode_res.success ? static_cast<jint>(pkt->lon_e7) : 0,
            tokens_array
    );

    return result_obj;
}

JNIEXPORT jbyteArray JNICALL
Java_com_team_vocalink_core_AirHopNative_verifyAndDecrementTTL(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray raw_packet_array) {

    ensure_initialized();

    if (raw_packet_array == nullptr || env->GetArrayLength(raw_packet_array) != PACKET_TOTAL_SIZE) {
        return nullptr;
    }

    uint8_t buffer[PACKET_TOTAL_SIZE];
    env->GetByteArrayRegion(raw_packet_array, 0, PACKET_TOTAL_SIZE, reinterpret_cast<jbyte*>(buffer));

    AirHopPacket* pkt = reinterpret_cast<AirHopPacket*>(buffer);
    if (pkt->preamble != AIRHOP_PREAMBLE) {
        return nullptr;
    }

    if (pkt->ttl <= 1) {
        // TTL expired: hop limit reached, drop frame
        return nullptr;
    }

    // Decrement TTL for blind relay
    pkt->ttl -= 1;

    // Recompute RS(40,32) parity over data bytes [0..31]
    g_rs->encode(buffer, pkt->fec_parity);

    jbyteArray out_array = env->NewByteArray(PACKET_TOTAL_SIZE);
    env->SetByteArrayRegion(out_array, 0, PACKET_TOTAL_SIZE, reinterpret_cast<const jbyte*>(buffer));
    return out_array;
}

JNIEXPORT jint JNICALL
Java_com_team_vocalink_core_AirHopNative_processAudioChunk(
        JNIEnv* env,
        jobject /* this */,
        jshortArray pcm_chunk) {

    ensure_initialized();

    if (pcm_chunk == nullptr) return 0;

    jsize len = env->GetArrayLength(pcm_chunk);
    if (len <= 0) return 0;

    jshort* samples = env->GetShortArrayElements(pcm_chunk, nullptr);

    std::lock_guard<std::mutex> lock(g_audio_mutex);
    // Write into zero-copy circular ring buffer
    g_audio_ring->write(reinterpret_cast<const int16_t*>(samples), static_cast<size_t>(len));

    // Step Silero VAD state machine
    VadEvent event = g_vad->process_chunk(reinterpret_cast<const int16_t*>(samples), static_cast<size_t>(len));
    VadState state = g_vad->current_state();

    env->ReleaseShortArrayElements(pcm_chunk, samples, JNI_ABORT);

    // Return (event << 4) | state
    return (static_cast<int>(event) << 4) | (static_cast<int>(state) & 0x0F);
}

JNIEXPORT jint JNICALL
Java_com_team_vocalink_core_AirHopNative_readAudioFromRingBuffer(
        JNIEnv* env,
        jobject /* this */,
        jshortArray destination_array) {

    ensure_initialized();

    if (destination_array == nullptr) return 0;

    jsize dest_len = env->GetArrayLength(destination_array);
    if (dest_len <= 0) return 0;

    jshort* dest = env->GetShortArrayElements(destination_array, nullptr);

    std::lock_guard<std::mutex> lock(g_audio_mutex);
    size_t read_count = g_audio_ring->read(reinterpret_cast<int16_t*>(dest), static_cast<size_t>(dest_len));

    env->ReleaseShortArrayElements(destination_array, dest, 0);
    return static_cast<jint>(read_count);
}

JNIEXPORT void JNICALL
Java_com_team_vocalink_core_AirHopNative_resetAudioEngine(
        JNIEnv* /* env */,
        jobject /* this */) {

    ensure_initialized();
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    g_audio_ring->clear();
    g_vad->reset();
    LOGI("Audio Engine & Silero VAD state machine reset.");
}

} // extern "C"
