#include "audio_ring_buffer.h"
#include <algorithm>
#include <cstring>

AudioRingBuffer::AudioRingBuffer(size_t capacity) {
    // Round up to nearest power of 2 for fast bitwise masking
    size_t cap = 1024;
    while (cap < capacity) {
        cap <<= 1;
    }
    buffer_size_ = cap;
    mask_ = cap - 1;
    buffer_.resize(buffer_size_, 0);
    write_pos_.store(0, std::memory_order_relaxed);
    read_pos_.store(0, std::memory_order_relaxed);
}

size_t AudioRingBuffer::available_read() const noexcept {
    size_t w = write_pos_.load(std::memory_order_acquire);
    size_t r = read_pos_.load(std::memory_order_relaxed);
    return w - r;
}

size_t AudioRingBuffer::available_write() const noexcept {
    return buffer_size_ - available_read();
}

void AudioRingBuffer::clear() noexcept {
    size_t w = write_pos_.load(std::memory_order_relaxed);
    read_pos_.store(w, std::memory_order_release);
}

size_t AudioRingBuffer::write(const int16_t* data, size_t count) noexcept {
    if (!data || count == 0) return 0;

    size_t avail = available_write();
    size_t to_write = std::min(count, avail);
    if (to_write == 0) return 0;

    size_t w = write_pos_.load(std::memory_order_relaxed);
    size_t idx = w & mask_;

    size_t first_chunk = std::min(to_write, buffer_size_ - idx);
    std::memcpy(&buffer_[idx], data, first_chunk * sizeof(int16_t));

    size_t second_chunk = to_write - first_chunk;
    if (second_chunk > 0) {
        std::memcpy(&buffer_[0], data + first_chunk, second_chunk * sizeof(int16_t));
    }

    write_pos_.store(w + to_write, std::memory_order_release);
    return to_write;
}

size_t AudioRingBuffer::read(int16_t* destination, size_t count) noexcept {
    if (!destination || count == 0) return 0;

    size_t avail = available_read();
    size_t to_read = std::min(count, avail);
    if (to_read == 0) return 0;

    size_t r = read_pos_.load(std::memory_order_relaxed);
    size_t idx = r & mask_;

    size_t first_chunk = std::min(to_read, buffer_size_ - idx);
    std::memcpy(destination, &buffer_[idx], first_chunk * sizeof(int16_t));

    size_t second_chunk = to_read - first_chunk;
    if (second_chunk > 0) {
        std::memcpy(destination + first_chunk, &buffer_[0], second_chunk * sizeof(int16_t));
    }

    read_pos_.store(r + to_read, std::memory_order_release);
    return to_read;
}

size_t AudioRingBuffer::peek(int16_t* destination, size_t count) const noexcept {
    if (!destination || count == 0) return 0;

    size_t avail = available_read();
    size_t to_read = std::min(count, avail);
    if (to_read == 0) return 0;

    size_t r = read_pos_.load(std::memory_order_relaxed);
    size_t idx = r & mask_;

    size_t first_chunk = std::min(to_read, buffer_size_ - idx);
    std::memcpy(destination, &buffer_[idx], first_chunk * sizeof(int16_t));

    size_t second_chunk = to_read - first_chunk;
    if (second_chunk > 0) {
        std::memcpy(destination + first_chunk, &buffer_[0], second_chunk * sizeof(int16_t));
    }

    return to_read;
}
