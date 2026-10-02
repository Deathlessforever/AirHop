#include "fnv1a.h"
#include <cstring>

static constexpr uint32_t FNV_OFFSET_BASIS = 2166136261u; // 0x811C9DC5
static constexpr uint32_t FNV_PRIME        = 16777619u;   // 0x01000193

uint32_t fnv1a_32(const uint8_t* data, size_t length) noexcept {
    uint32_t hash = FNV_OFFSET_BASIS;
    for (size_t i = 0; i < length; ++i) {
        hash ^= data[i];
        hash *= FNV_PRIME;
    }
    return hash;
}

uint32_t compute_airhop_msg_id(int32_t lat_e7, int32_t lon_e7, uint64_t timestamp_ms, const uint8_t* tokens, size_t num_tokens) noexcept {
    // Pack fields into contiguous memory buffer for deterministic hash calculation
    uint8_t buffer[4 + 4 + 8 + 13];
    size_t offset = 0;

    std::memcpy(buffer + offset, &lat_e7, sizeof(lat_e7));
    offset += sizeof(lat_e7);

    std::memcpy(buffer + offset, &lon_e7, sizeof(lon_e7));
    offset += sizeof(lon_e7);

    std::memcpy(buffer + offset, &timestamp_ms, sizeof(timestamp_ms));
    offset += sizeof(timestamp_ms);

    size_t copy_tokens = (num_tokens > 13) ? 13 : num_tokens;
    std::memcpy(buffer + offset, tokens, copy_tokens);
    if (copy_tokens < 13) {
        std::memset(buffer + offset + copy_tokens, 0, 13 - copy_tokens);
    }
    offset += 13;

    return fnv1a_32(buffer, offset);
}
