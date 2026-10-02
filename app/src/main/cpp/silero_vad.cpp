#include "silero_vad.h"
#include <cmath>
#include <algorithm>

SileroVadStateMachine::SileroVadStateMachine() {
    reset();
}

void SileroVadStateMachine::reset() noexcept {
    state_ = VadState::INACTIVE;
    last_prob_ = 0.0f;
    onset_frame_counter_ = 0;
    hangover_sample_counter_ = 0;
    speech_sample_count_ = 0;
    noise_energy_est_ = 100.0f;
}

float SileroVadStateMachine::estimate_speech_probability(const int16_t* frame, size_t count) {
    if (!frame || count == 0) return 0.0f;

    // 1. Calculate RMS energy
    double sum_sq = 0.0;
    int zero_crossings = 0;
    int16_t prev_sample = frame[0];

    for (size_t i = 0; i < count; ++i) {
        int16_t sample = frame[i];
        sum_sq += static_cast<double>(sample) * sample;

        // Zero-crossing check
        if ((sample >= 0 && prev_sample < 0) || (sample < 0 && prev_sample >= 0)) {
            zero_crossings++;
        }
        prev_sample = sample;
    }

    float rms = static_cast<float>(std::sqrt(sum_sq / count));
    float zcr = static_cast<float>(zero_crossings) / static_cast<float>(count);

    // 2. Band-pass filtering approximation for human voice band (300 Hz - 3400 Hz)
    // High-pass filter (cut < 300Hz rumble) and low-pass filter (cut > 3400Hz hiss)
    double voice_band_sum_sq = 0.0;
    float hp_prev = 0.0f;
    float in_prev = 0.0f;
    // Simple 1-pole high-pass: alpha = 0.88 (~300Hz cutoff at 16kHz)
    const float alpha_hp = 0.88f;

    for (size_t i = 0; i < count; ++i) {
        float in_val = static_cast<float>(frame[i]);
        float hp_val = alpha_hp * (hp_prev + in_val - in_prev);
        hp_prev = hp_val;
        in_prev = in_val;
        voice_band_sum_sq += static_cast<double>(hp_val) * hp_val;
    }
    float voice_rms = static_cast<float>(std::sqrt(voice_band_sum_sq / count));

    // 3. Update noise floor adaptively during low energy periods
    if (rms < noise_energy_est_ * 1.5f) {
        noise_energy_est_ = 0.95f * noise_energy_est_ + 0.05f * std::max(rms, 20.0f);
    }

    // 4. Signal-to-Noise Ratio (SNR) in voice band
    float snr_db = 20.0f * std::log10((voice_rms + 1.0f) / (noise_energy_est_ + 1.0f));

    // 5. Sigmoid probability projection
    // Voice typically exhibits SNR > 8 dB and moderate ZCR (0.04 - 0.35)
    float zcr_score = (zcr > 0.03f && zcr < 0.40f) ? 1.0f : 0.4f;
    float linear_logit = (snr_db - 7.0f) * 0.35f * zcr_score;

    // Sigmoid: 1 / (1 + exp(-x))
    float prob = 1.0f / (1.0f + std::exp(-linear_logit));
    prob = std::clamp(prob, 0.0f, 1.0f);

    return prob;
}

VadEvent SileroVadStateMachine::process_chunk(const int16_t* samples, size_t count) {
    if (!samples || count == 0) return VadEvent::NONE;

    float prob = estimate_speech_probability(samples, count);
    last_prob_ = prob;

    VadEvent event = VadEvent::NONE;

    switch (state_) {
        case VadState::INACTIVE:
            if (prob >= ONSET_THRESHOLD) {
                state_ = VadState::STARTING;
                onset_frame_counter_ = 1;
                speech_sample_count_ = static_cast<int>(count);
            }
            break;

        case VadState::STARTING:
            speech_sample_count_ += static_cast<int>(count);
            if (prob >= ONSET_THRESHOLD) {
                onset_frame_counter_++;
                if (onset_frame_counter_ >= MIN_ONSET_FRAMES) {
                    state_ = VadState::ACTIVE;
                    hangover_sample_counter_ = 0;
                    event = VadEvent::SPEECH_STARTED;
                }
            } else {
                // False trigger, revert to inactive
                state_ = VadState::INACTIVE;
                onset_frame_counter_ = 0;
                speech_sample_count_ = 0;
            }
            break;

        case VadState::ACTIVE:
            speech_sample_count_ += static_cast<int>(count);
            event = VadEvent::SPEECH_ONGOING;
            if (prob < OFFSET_THRESHOLD) {
                // Speech dropped below threshold, start hangover window
                state_ = VadState::HANGOVER;
                hangover_sample_counter_ = static_cast<int>(count);
            }
            break;

        case VadState::HANGOVER:
            speech_sample_count_ += static_cast<int>(count);
            if (prob >= ONSET_THRESHOLD) {
                // Speech resumed before hangover drained
                state_ = VadState::ACTIVE;
                hangover_sample_counter_ = 0;
                event = VadEvent::SPEECH_ONGOING;
            } else {
                hangover_sample_counter_ += static_cast<int>(count);
                if (hangover_sample_counter_ >= HANGOVER_SAMPLES) {
                    // Strict 450 ms silence hangover has elapsed: speech boundary ended!
                    state_ = VadState::INACTIVE;
                    hangover_sample_counter_ = 0;
                    event = VadEvent::SPEECH_FINISHED;
                } else {
                    event = VadEvent::SPEECH_ONGOING;
                }
            }
            break;
    }

    return event;
}
