#include <iostream>
#include <vector>
#include <cassert>
#include <cstring>
#include <random>

#include "../../main/cpp/gf256.h"
#include "../../main/cpp/reed_solomon.h"
#include "../../main/cpp/fnv1a.h"
#include "../../main/cpp/airhop_packet.h"

int main() {
    std::cout << "========================================================\n";
    std::cout << " AIRHOP NATIVE CORE: RS(40,32) & GF(2^8) VERIFICATION  \n";
    std::cout << "========================================================\n";

    // 1. Verify Packet Size
    assert(sizeof(AirHopPacket) == 40);
    std::cout << "[PASS] sizeof(AirHopPacket) is exactly 40 bytes.\n";

    // 2. Verify GF256 arithmetic
    GF256& gf = GF256::instance();
    // Test basic identity: a * inv(a) == 1 for all a in [1..255]
    for (int a = 1; a < 256; ++a) {
        uint8_t inv_a = gf.inv(static_cast<uint8_t>(a));
        uint8_t prod = gf.mul(static_cast<uint8_t>(a), inv_a);
        if (prod != 1) {
            std::cerr << "[FAIL] GF256 inv test failed for " << a << " prod=" << (int)prod << "\n";
            return 1;
        }
    }
    std::cout << "[PASS] GF(2^8) multiplicative inverse verified for all 255 non-zero elements.\n";

    // 3. Verify FNV-1a Hash
    uint8_t tokens[13] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
    uint32_t hash1 = compute_airhop_msg_id(12295800, 76639300, 1000000, tokens, 13);
    uint32_t hash2 = compute_airhop_msg_id(12295800, 76639300, 1000000, tokens, 13);
    assert(hash1 == hash2);
    assert(hash1 != 0);
    std::cout << "[PASS] 32-bit FNV-1a deterministic hash verified (0x" << std::hex << hash1 << std::dec << ").\n";

    // 4. Verify Reed-Solomon RS(40,32)
    ReedSolomon rs;

    // Create 32 data bytes
    uint8_t original_packet[40];
    for (size_t i = 0; i < 32; ++i) {
        original_packet[i] = static_cast<uint8_t>((i * 7 + 13) & 0xFF);
    }
    original_packet[0] = 0x7E; // Preamble

    // Encode 8 parity bytes into original_packet[32..39]
    rs.encode(original_packet, original_packet + 32);

    // Verify syndromes on clean codeword
    std::array<uint8_t, 8> syn{};
    bool clean = rs.compute_syndromes(original_packet, syn);
    assert(clean);
    std::cout << "[PASS] RS(40,32) clean codeword syndrome check passed (all syndromes 0).\n";

    // Test zero errors decode
    uint8_t test_buf[40];
    std::memcpy(test_buf, original_packet, 40);
    RsDecodeResult res0 = rs.decode_and_repair(test_buf);
    assert(res0.success && res0.corrected_bytes == 0 && !res0.had_errors);
    std::cout << "[PASS] RS(40,32) 0 errors: detected clean.\n";

    // Test 1, 2, 3, 4 corrupted bytes correction
    for (int num_errors = 1; num_errors <= 4; ++num_errors) {
        std::memcpy(test_buf, original_packet, 40);

        // Pick distinct positions
        int positions[4] = {3, 14, 27, 36};
        for (int e = 0; e < num_errors; ++e) {
            int pos = positions[e];
            test_buf[pos] ^= static_cast<uint8_t>(0xA5 + e * 17); // Invert bits
        }

        RsDecodeResult res = rs.decode_and_repair(test_buf);
        if (!res.success) {
            std::cerr << "[FAIL] Failed to correct " << num_errors << " errors!\n";
            return 1;
        }

        assert(res.corrected_bytes == num_errors);
        assert(std::memcmp(test_buf, original_packet, 40) == 0);
        std::cout << "[PASS] RS(40,32) " << num_errors << " byte error(s) automatically detected and repaired!\n";
    }

    // Test 5 errors (exceeds t=4)
    std::memcpy(test_buf, original_packet, 40);
    int pos5[5] = {1, 7, 19, 29, 38};
    for (int e = 0; e < 5; ++e) {
        test_buf[pos5[e]] ^= static_cast<uint8_t>(0x5A + e);
    }
    RsDecodeResult res5 = rs.decode_and_repair(test_buf);
    assert(!res5.success); // Must safely fail rather than corrupt
    std::cout << "[PASS] RS(40,32) 5 byte errors correctly identified as uncorrectable (>4 errors).\n";

    std::cout << "\n>>> ALL NATIVE C++ MATHEMATICAL VERIFICATIONS PASSED 100% <<<\n";
    return 0;
}
