#ifndef SILERO_VAD_H
#define SILERO_VAD_H

#include <cstdint>
#include <cstddef>

enum class VadState : int {
    INACTIVE = 0,   // Ambient background / silence
    STARTING = 1,   // Speech onset candidate
    ACTIVE   = 2,   // Active speech frame
    HANGOVER = 3    // In 450ms silence hangover window
};

enum class VadEvent : int {
    NONE            = 0,
    SPEECH_STARTED  = 1,
    SPEECH_ONGOING  = 2,
    SPEECH_FINISHED = 3
};

class SileroVadStateMachine {
public:
    static constexpr int SAMPLE_RATE = 16000;
    static constexpr int FRAME_SIZE = 512;               // 32 ms per frame at 16 kHz
    static constexpr int HANGOVER_SAMPLES = 7200;         // Exactly 450 ms at 16 kHz (450 * 16)
    static constexpr int MIN_ONSET_FRAMES = 2;           // ~64 ms continuous speech for onset lock
    static constexpr float ONSET_THRESHOLD = 0.50f;      // Speech start probability threshold
    static constexpr float OFFSET_THRESHOLD = 0.35f;     // Silence onset threshold

    SileroVadStateMachine();

    /**
     * Ingests a 16 kHz mono PCM chunk and updates the VAD state machine.
     * @param samples Pointer to 16-bit PCM samples
     * @param count Number of samples
     * @return VadEvent signifying whether speech started, is ongoing, or finished.
     */
    VadEvent process_chunk(const int16_t* samples, size_t count);

    VadState current_state() const noexcept { return state_; }
    float last_probability() const noexcept { return last_prob_; }
    int total_speech_samples() const noexcept { return speech_sample_count_; }
    void reset() noexcept;

private:
    float estimate_speech_probability(const int16_t* frame, size_t count);

    VadState state_{VadState::INACTIVE};
    float last_prob_{0.0f};
    int onset_frame_counter_{0};
    int hangover_sample_counter_{0};
    int speech_sample_count_{0};

    // Adaptive noise floor tracking
    float noise_energy_est_{100.0f};
};

#endif // SILERO_VAD_H
