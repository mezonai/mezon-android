#pragma once

#include <array>
#include <cstdint>
#include "mezon_ns.h"

class MezonNs48k {
public:
    explicit MezonNs48k(MezonNSEngine* engine);
    ~MezonNs48k();

    int process(int16_t* frame);
    void reset();
    MezonNSEngine* core() const { return engine_; }

private:
    static constexpr int kTaps = 120;
    static constexpr int kPhaseTaps = kTaps / 3;
    static constexpr int kInputSamples = 480;
    static constexpr int kModelSamples = 160;

    MezonNSEngine* engine_;
    std::array<float, kTaps> taps_{};
    std::array<float, kTaps - 1> down_history_{};
    std::array<float, kPhaseTaps - 1> up_history_{};
};
