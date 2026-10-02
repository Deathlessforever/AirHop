#ifndef GF256_H
#define GF256_H

#include <cstdint>
#include <array>

class GF256 {
public:
    static constexpr uint16_t PRIMITIVE_POLY = 0x011D; // x^8 + x^4 + x^3 + x^2 + 1
    static constexpr size_t FIELD_SIZE = 256;

    // Singleton or globally initialized tables
    static GF256& instance();

    // Fundamental Galois Field arithmetic operations
    uint8_t add(uint8_t a, uint8_t b) const noexcept {
        return a ^ b;
    }

    uint8_t sub(uint8_t a, uint8_t b) const noexcept {
        return a ^ b; // In GF(2^8), subtraction equals addition (XOR)
    }

    uint8_t mul(uint8_t a, uint8_t b) const noexcept {
        if (a == 0 || b == 0) return 0;
        return exp_table[log_table[a] + log_table[b]];
    }

    uint8_t div(uint8_t a, uint8_t b) const noexcept {
        if (a == 0) return 0;
        if (b == 0) return 0; // Guard against division by zero
        int diff = static_cast<int>(log_table[a]) - static_cast<int>(log_table[b]);
        if (diff < 0) diff += 255;
        return exp_table[diff];
    }

    uint8_t inv(uint8_t a) const noexcept {
        if (a == 0) return 0;
        return exp_table[255 - log_table[a]];
    }

    uint8_t exp(int power) const noexcept {
        // Safe access for positive or negative powers
        int p = power % 255;
        if (p < 0) p += 255;
        return exp_table[p];
    }

    uint8_t log(uint8_t val) const noexcept {
        return log_table[val];
    }

    uint8_t poly_eval(const uint8_t* poly, size_t deg_plus_one, uint8_t x) const noexcept;

private:
    GF256();
    void init_tables();

    std::array<uint8_t, 512> exp_table{}; // Doubled size for branchless multiplication
    std::array<uint8_t, 256> log_table{};
};

#endif // GF256_H
