#include "reed_solomon.h"
#include <cstring>
#include <vector>

ReedSolomon::ReedSolomon() : gf(GF256::instance()) {
    init_generator_poly();
}

void ReedSolomon::init_generator_poly() {
    // g(x) = prod_{i=0}^{7} (x + alpha^i)
    // Degree of g(x) is 8 -> 9 coefficients
    gen_poly.fill(0);
    gen_poly[0] = 1; // Start with g(x) = 1 (monic)

    for (size_t i = 0; i < PARITY_SIZE; ++i) {
        uint8_t root = gf.exp(static_cast<int>(i));
        // Multiply current polynomial by (x + root)
        // (a0 x^k + a1 x^(k-1) + ... + ak) * (x + root)
        // new_coeff[j] = old_coeff[j] * root + old_coeff[j-1]
        for (size_t j = i + 1; j > 0; --j) {
            gen_poly[j] = gf.add(gf.mul(gen_poly[j], root), gen_poly[j - 1]);
        }
        gen_poly[0] = gf.mul(gen_poly[0], root);
    }

    // Now gen_poly has gen_poly[0] as lowest power and gen_poly[8] as highest (x^8).
    // Let's normalize so gen_poly[0] is the coefficient of x^8 (monic leading term)
    // to match standard synthetic division order:
    // P(x) = gen_poly[0]*x^8 + gen_poly[1]*x^7 + ... + gen_poly[8]
    std::array<uint8_t, PARITY_SIZE + 1> rev;
    for (size_t i = 0; i <= PARITY_SIZE; ++i) {
        rev[i] = gen_poly[PARITY_SIZE - i];
    }
    gen_poly = rev;
}

void ReedSolomon::encode(const uint8_t* data, uint8_t* out_parity) const {
    // Systematic encoding via LFSR synthetic division
    std::array<uint8_t, PARITY_SIZE> rem{};
    rem.fill(0);

    for (size_t i = 0; i < K; ++i) {
        uint8_t feedback = gf.add(data[i], rem[0]);
        for (size_t j = 0; j < PARITY_SIZE - 1; ++j) {
            rem[j] = gf.add(rem[j + 1], gf.mul(feedback, gen_poly[j + 1]));
        }
        rem[PARITY_SIZE - 1] = gf.mul(feedback, gen_poly[PARITY_SIZE]);
    }

    std::memcpy(out_parity, rem.data(), PARITY_SIZE);
}

bool ReedSolomon::compute_syndromes(const uint8_t* codeword, std::array<uint8_t, PARITY_SIZE>& syndromes) const {
    bool has_nonzero = false;
    for (size_t i = 0; i < PARITY_SIZE; ++i) {
        uint8_t alpha_i = gf.exp(static_cast<int>(i));
        // Evaluate codeword polynomial at alpha^i using Horner's method
        uint8_t syn = gf.poly_eval(codeword, N, alpha_i);
        syndromes[i] = syn;
        if (syn != 0) {
            has_nonzero = true;
        }
    }
    return !has_nonzero; // true if clean (all zero)
}

RsDecodeResult ReedSolomon::decode_and_repair(uint8_t* codeword) const {
    RsDecodeResult result{false, 0, false};

    std::array<uint8_t, PARITY_SIZE> syndromes{};
    bool clean = compute_syndromes(codeword, syndromes);
    if (clean) {
        result.success = true;
        result.corrected_bytes = 0;
        result.had_errors = false;
        return result;
    }

    result.had_errors = true;

    // 1. Berlekamp-Massey Algorithm to find Error Locator Polynomial Lambda(x)
    // Lambda(x) = 1 + Lambda_1*x + ... + Lambda_L*x^L
    std::vector<uint8_t> Lambda = {1};
    std::vector<uint8_t> B = {1};
    int L = 0;
    int m = 1;
    uint8_t b = 1;

    for (size_t n = 0; n < PARITY_SIZE; ++n) {
        // Discrepancy: Delta = S_n + sum_{i=1}^L (Lambda_i * S_{n-i})
        uint8_t delta = syndromes[n];
        for (size_t i = 1; i <= static_cast<size_t>(L); ++i) {
            if (i < Lambda.size()) {
                delta = gf.add(delta, gf.mul(Lambda[i], syndromes[n - i]));
            }
        }

        if (delta == 0) {
            m++;
        } else {
            std::vector<uint8_t> T = Lambda;

            // factor = delta / b
            uint8_t factor = gf.div(delta, b);

            // Lambda(x) = Lambda(x) + factor * x^m * B(x)
            size_t needed_size = m + B.size();
            if (Lambda.size() < needed_size) {
                Lambda.resize(needed_size, 0);
            }
            for (size_t i = 0; i < B.size(); ++i) {
                Lambda[i + m] = gf.add(Lambda[i + m], gf.mul(factor, B[i]));
            }

            if (2 * L <= static_cast<int>(n)) {
                L = static_cast<int>(n) + 1 - L;
                B = T;
                b = delta;
                m = 1;
            } else {
                m++;
            }
        }
    }

    // Shrink Lambda trailing zeroes
    while (Lambda.size() > 1 && Lambda.back() == 0) {
        Lambda.pop_back();
    }

    // Number of errors is degree of Lambda
    int num_errors = static_cast<int>(Lambda.size()) - 1;
    if (num_errors <= 0 || num_errors > static_cast<int>(MAX_ERRORS)) {
        // More than 4 errors or degenerate polynomial -> uncorrectable
        return result;
    }

    // 2. Chien Search to find roots of Lambda(x)
    // Roots of Lambda(alpha^-j) == 0 correspond to error at power x^j
    // Codeword has index pos = (N - 1) - j, where j in [0, N-1]
    std::vector<int> error_positions;
    std::vector<uint8_t> error_locators; // X_l = alpha^j

    for (size_t j = 0; j < N; ++j) {
        uint8_t alpha_neg_j = gf.exp(-static_cast<int>(j));
        // Evaluate Lambda at alpha^-j: Lambda(0) + Lambda(1)*z + ...
        uint8_t sum = 0;
        uint8_t z_pow = 1;
        for (size_t deg = 0; deg < Lambda.size(); ++deg) {
            sum = gf.add(sum, gf.mul(Lambda[deg], z_pow));
            z_pow = gf.mul(z_pow, alpha_neg_j);
        }

        if (sum == 0) {
            // Root found!
            size_t pos = (N - 1) - j;
            error_positions.push_back(static_cast<int>(pos));
            error_locators.push_back(gf.exp(static_cast<int>(j)));
        }
    }

    if (error_positions.size() != static_cast<size_t>(num_errors)) {
        // Roots were outside codeword bounds or repeated -> uncorrectable
        return result;
    }

    // 3. Forney Algorithm for Error Magnitudes
    // Omega(x) = [Syndromes(x) * Lambda(x)] mod x^(PARITY_SIZE)
    std::vector<uint8_t> Omega(PARITY_SIZE, 0);
    for (size_t i = 0; i < PARITY_SIZE; ++i) {
        for (size_t j = 0; j < Lambda.size(); ++j) {
            if (i + j < PARITY_SIZE) {
                Omega[i + j] = gf.add(Omega[i + j], gf.mul(syndromes[i], Lambda[j]));
            }
        }
    }

    // Formal derivative of Lambda(x): Lambda'(x) = sum_{k odd} Lambda[k] * x^(k-1)
    std::vector<uint8_t> Lambda_prime;
    if (Lambda.size() > 1) {
        Lambda_prime.resize(Lambda.size() - 1, 0);
        for (size_t k = 1; k < Lambda.size(); k += 2) {
            Lambda_prime[k - 1] = Lambda[k];
        }
    }

    // Correct each error
    for (size_t l = 0; l < error_positions.size(); ++l) {
        uint8_t X_l = error_locators[l];
        uint8_t z = gf.inv(X_l); // z = X_l^-1

        // Evaluate Omega(z)
        uint8_t num = 0;
        uint8_t z_pow = 1;
        for (size_t i = 0; i < Omega.size(); ++i) {
            num = gf.add(num, gf.mul(Omega[i], z_pow));
            z_pow = gf.mul(z_pow, z);
        }

        // Evaluate Lambda'(z)
        uint8_t den = 0;
        z_pow = 1;
        for (size_t i = 0; i < Lambda_prime.size(); ++i) {
            den = gf.add(den, gf.mul(Lambda_prime[i], z_pow));
            z_pow = gf.mul(z_pow, z);
        }

        if (den == 0) {
            return result; // Mathematical inconsistency guard
        }

        // Error magnitude Y_l = X_l * Omega(z) / Lambda'(z)
        uint8_t Y_l = gf.mul(X_l, gf.div(num, den));

        int pos = error_positions[l];
        codeword[pos] = gf.add(codeword[pos], Y_l);
    }

    // 4. Verify repaired codeword syndromes
    std::array<uint8_t, PARITY_SIZE> check_syndromes{};
    if (compute_syndromes(codeword, check_syndromes)) {
        result.success = true;
        result.corrected_bytes = static_cast<int>(error_positions.size());
    } else {
        result.success = false;
    }

    return result;
}
