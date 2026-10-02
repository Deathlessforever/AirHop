#ifndef AUDIO_RING_BUFFER_H
#define AUDIO_RING_BUFFER_H

#include <cstdint>
#include <cstddef>
#include <atomic>
#include <vector>

/**
 * Lock-free Single-Producer Single-Consumer (SPSC) circular audio ring buffer
 * Optimized for 16 kHz, 16-bit mono PCM audio ingest from AudioRecord JNI callbacks.
 */
class AudioRingBuffer {
public:
    static constexpr size_t DEFAULT_CAPACITY = 65536; // ~4.096 seconds at 16 kHz (power of 2)

    explicit AudioRingBuffer(size_t capacity = DEFAULT_CAPACITY);
    ~AudioRingBuffer() = default;

    // Prevent copies
    AudioRingBuffer(const AudioRingBuffer&) = delete;
    AudioRingBuffer& operator=(const AudioRingBuffer&) = delete;

    size_t write(const int16_t* data, size_t count) noexcept;
    size_t read(int16_t* destination, size_t count) noexcept;
    size_t peek(int16_t* destination, size_t count) const noexcept;

    size_t available_read() const noexcept;
    size_t available_write() const noexcept;
    void clear() noexcept;

    size_t capacity() const noexcept { return buffer_size_; }

private:
    std::vector<int16_t> buffer_;
    size_t buffer_size_;
    size_t mask_;
    alignas(64) std::atomic<size_t> write_pos_{0};
    alignas(64) std::atomic<size_t> read_pos_{0};
};

#endif // AUDIO_RING_BUFFER_H
