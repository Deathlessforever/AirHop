#ifndef FNV1A_H
#define FNV1A_H

#include <cstdint>
#include <cstddef>

/**
 * 32-bit FNV-1a Hash (Fowler-Noll-Vo)
 * Offset basis: 0x811C9DC5
 * FNV prime:    0x01000193
 */
uint32_t fnv1a_32(const uint8_t* data, size_t length) noexcept;

/**
 * Computes deterministic 32-bit msg_id over lat, lon, timestamp, and 13 phonemic tokens.
 */
uint32_t compute_airhop_msg_id(int32_t lat_e7, int32_t lon_e7, uint64_t timestamp_ms, const uint8_t* tokens, size_t num_tokens) noexcept;

#endif // FNV1A_H
