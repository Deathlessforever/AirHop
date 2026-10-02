#ifndef REED_SOLOMON_H
#define REED_SOLOMON_H

#include <cstdint>
#include <cstddef>
#include <array>
#include "gf256.h"

struct RsDecodeResult {
    bool success;
    int corrected_bytes;
    bool had_errors;
};

class ReedSolomon {
public:
    static constexpr size_t N = 40;  // Total codeword bytes
    static constexpr size_t K = 32;  // Data payload bytes
    static constexpr size_t PARITY_SIZE = 8; // 2t = 8 parity bytes
    static constexpr size_t MAX_ERRORS = 4;  // t = 4 correctable symbol errors

    ReedSolomon();

    /**
     * Encodes 32 data bytes into 8 parity bytes.
     * @param data Pointer to 32 bytes of message data
     * @param out_parity Pointer to output buffer for 8 parity bytes
     */
    void encode(const uint8_t* data, uint8_t* out_parity) const;

    /**
     * Decodes and repairs up to 4 corrupted bytes in a 40-byte codeword in-place.
     * @param codeword 40-byte packet buffer (data[32] + parity[8])
     * @return RsDecodeResult with success flag, count of corrected bytes, and error occurrence
     */
    RsDecodeResult decode_and_repair(uint8_t* codeword) const;

    /**
     * Computes the 8 syndromes for a 40-byte codeword.
     * @param codeword 40-byte packet buffer
     * @param syndromes Output array of 8 syndromes
     * @return true if all syndromes are zero (codeword clean), false otherwise
     */
    bool compute_syndromes(const uint8_t* codeword, std::array<uint8_t, PARITY_SIZE>& syndromes) const;

private:
    void init_generator_poly();

    // Generator polynomial coefficients: g(x) = g_0*x^8 + g_1*x^7 + ... + g_8
    std::array<uint8_t, PARITY_SIZE + 1> gen_poly{};
    GF256& gf;
};

#endif // REED_SOLOMON_H
