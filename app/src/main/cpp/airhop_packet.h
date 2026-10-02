#ifndef AIRHOP_PACKET_H
#define AIRHOP_PACKET_H

#include <cstdint>
#include <cstddef>

#pragma pack(push, 1)

/**
 * AirHopPacket: Exact 40-byte packed frame for long-range disaster mesh transmission.
 * Formatted for BLE Coded PHY (S=8) extended non-connectable anonymous advertisements
 * and high-density Wi-Fi Aware (NAN) frames.
 */
struct AirHopPacket {
    uint8_t  preamble;       // Synchronization marker: strictly 0x7E
    uint8_t  flags;          // Bit 7: Emergency SOS, Bit 6: High Priority, Bit 5: Urgent, Bits 4..0: Lang ID
    uint8_t  ttl;            // Hop count limit (default 10 hops, decremented at each node)
    uint32_t msg_id;         // 32-bit FNV-1a hash over lat/lon/timestamp/tokens
    uint32_t target_zone;    // Geofence cluster hash identifier
    int32_t  lat_e7;         // NavIC/GPS Latitude scaled by 1e7
    int32_t  lon_e7;         // NavIC/GPS Longitude scaled by 1e7
    uint8_t  tokens[13];     // 13 phonemic indices from neural voice encoder
    uint8_t  fec_parity[8];  // Reed-Solomon RS(40,32) forward error correction parity
};

#pragma pack(pop)

static_assert(sizeof(AirHopPacket) == 40, "AirHopPacket MUST be exactly 40 bytes");

// Protocol Constants
constexpr uint8_t AIRHOP_PREAMBLE = 0x7E;
constexpr uint8_t DEFAULT_TTL = 10;

// Flag bitmasks
constexpr uint8_t FLAG_EMERGENCY_SOS   = 0x80; // Bit 7: 1 = Emergency SOS active
constexpr uint8_t FLAG_PRIORITY_HIGH   = 0x40; // Bit 6: 1 = High priority
constexpr uint8_t FLAG_PRIORITY_URGENT = 0x20; // Bit 5: 1 = Urgent broadcast
constexpr uint8_t FLAG_LANG_MASK       = 0x1F; // Bits 0..4: Language Code

// Language IDs
constexpr uint8_t LANG_KANNADA = 0x01; // Primary Indic target for ISRO SIH
constexpr uint8_t LANG_HINDI   = 0x02; // Secondary Indic target
constexpr uint8_t LANG_ENGLISH = 0x03;

// Field offsets
constexpr size_t PACKET_TOTAL_SIZE = 40;
constexpr size_t PACKET_DATA_SIZE  = 32; // 40 - 8 parity bytes
constexpr size_t PACKET_FEC_SIZE   = 8;  // 8 parity bytes

#endif // AIRHOP_PACKET_H
