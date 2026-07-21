// spsc_ring.h — single-producer / single-consumer lock-free byte ring.
//
// Producer: the audio I/O thread (sc_push_capture). Consumer: the worker
// thread. Capacity is fixed at construction; the producer never allocates,
// locks, or blocks. Records are framed as [RecordHeader][payload bytes].
#ifndef SYNCCORE_SPSC_RING_H
#define SYNCCORE_SPSC_RING_H

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

namespace synccore {

struct RecordHeader {
    uint32_t payload_bytes;
    uint32_t frames;
    uint64_t capture_mono_ns;
};

class SpscRing {
public:
    // capacity_bytes is rounded up to a power of two.
    explicit SpscRing(size_t capacity_bytes) {
        size_t cap = 1;
        while (cap < capacity_bytes) cap <<= 1;
        buf_.resize(cap);
        mask_ = cap - 1;
    }

    size_t capacity() const { return buf_.size(); }

    // Producer side. Returns false (and writes nothing) if the record does
    // not fit in the free space. Wait-free: two relaxed/acquire loads, a
    // bounded memcpy, one release store.
    bool try_write(const RecordHeader& hdr, const float* payload) {
        const size_t need = sizeof(RecordHeader) + hdr.payload_bytes;
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        if (need > buf_.size() - (head - tail)) return false;
        write_bytes(head, &hdr, sizeof(RecordHeader));
        write_bytes(head + sizeof(RecordHeader), payload, hdr.payload_bytes);
        head_.store(head + need, std::memory_order_release);
        return true;
    }

    // Consumer side. Copies the next record's header and appends its payload
    // (as floats) to out. Returns false if the ring is empty. May grow `out`
    // (consumer thread is non-RT).
    bool try_read(RecordHeader* hdr, std::vector<float>* out) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        if (head == tail) return false;
        read_bytes(tail, hdr, sizeof(RecordHeader));
        const size_t old_size = out->size();
        out->resize(old_size + hdr->payload_bytes / sizeof(float));
        read_bytes(tail + sizeof(RecordHeader), out->data() + old_size,
                   hdr->payload_bytes);
        tail_.store(tail + sizeof(RecordHeader) + hdr->payload_bytes,
                    std::memory_order_release);
        return true;
    }

private:
    void write_bytes(size_t pos, const void* src, size_t n) {
        const size_t at = pos & mask_;
        const size_t first = std::min(n, buf_.size() - at);
        std::memcpy(buf_.data() + at, src, first);
        if (first < n)
            std::memcpy(buf_.data(), static_cast<const uint8_t*>(src) + first,
                        n - first);
    }

    void read_bytes(size_t pos, void* dst, size_t n) const {
        const size_t at = pos & mask_;
        const size_t first = std::min(n, buf_.size() - at);
        std::memcpy(dst, buf_.data() + at, first);
        if (first < n)
            std::memcpy(static_cast<uint8_t*>(dst) + first, buf_.data(),
                        n - first);
    }

    std::vector<uint8_t> buf_;
    size_t mask_ = 0;
    alignas(64) std::atomic<size_t> head_{0};  // producer-owned
    alignas(64) std::atomic<size_t> tail_{0};  // consumer-owned
};

}  // namespace synccore

#endif  // SYNCCORE_SPSC_RING_H
