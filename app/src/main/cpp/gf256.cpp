#include "gf256.h"

GF256& GF256::instance() {
    static GF256 inst;
    return inst;
}

GF256::GF256() {
    init_tables();
}

void GF256::init_tables() {
    // Generate exponential and logarithm tables over GF(2^8) with primitive poly 0x11D
    uint16_t root = 1;
    for (int i = 0; i < 255; ++i) {
        exp_table[i] = static_cast<uint8_t>(root);
        log_table[root] = static_cast<uint8_t>(i);
        root <<= 1;
        if (root & 0x0100) {
            root ^= PRIMITIVE_POLY;
        }
    }
    // Duplicate the upper half of the exponential table for modulo-free multiplication
    for (int i = 255; i < 512; ++i) {
        exp_table[i] = exp_table[i - 255];
    }
    log_table[0] = 0; // Undefined mathematically, set to 0 for safety
}

uint8_t GF256::poly_eval(const uint8_t* poly, size_t deg_plus_one, uint8_t x) const noexcept {
    // Horner's method for polynomial evaluation: poly[0]*x^n + poly[1]*x^(n-1) + ... + poly[n]
    if (deg_plus_one == 0) return 0;
    uint8_t y = poly[0];
    for (size_t i = 1; i < deg_plus_one; ++i) {
        y = add(mul(y, x), poly[i]);
    }
    return y;
}
