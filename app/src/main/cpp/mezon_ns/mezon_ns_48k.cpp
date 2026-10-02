#include "mezon_ns_48k.hpp"

#include <algorithm>
#include <cmath>

namespace {

double bessel_i0(double x) {
    const double half = x / 2.0;
    double sum = 1.0;
    double term = 1.0;
    for (int k = 1; k < 64; ++k) {
        const double ratio = half / k;
        term *= ratio * ratio;
        sum += term;
        if (term < 1e-12 * sum) break;
    }
    return sum;
}

}  // namespace

MezonNs48k::MezonNs48k(MezonNSEngine* engine) : engine_(engine) {
    constexpr double kPi = 3.14159265358979323846;
    const double center = (kTaps - 1) / 2.0;
    const double cutoff = 7200.0 / 48000.0;
    const double norm = bessel_i0(5.0);
    double sum = 0.0;
    for (int i = 0; i < kTaps; ++i) {
        const double offset = i - center;
        const double ratio = offset / center;
        const double window = bessel_i0(5.0 * std::sqrt(std::max(0.0, 1.0 - ratio * ratio))) / norm;
        const double x = 2.0 * cutoff * offset;
        const double sinc = x == 0.0 ? 1.0 : std::sin(kPi * x) / (kPi * x);
        const double tap = 2.0 * cutoff * sinc * window;
        taps_[i] = static_cast<float>(tap);
        sum += tap;
    }
    for (auto& tap : taps_) tap = static_cast<float>(tap / sum);
}

MezonNs48k::~MezonNs48k() {
    mezon_ns_destroy(engine_);
}

void MezonNs48k::reset() {
    mezon_ns_reset(engine_);
    down_history_.fill(0.0f);
    up_history_.fill(0.0f);
}

int MezonNs48k::process(int16_t* frame) {
    if (!frame || !engine_) return -1;

    std::array<float, kTaps - 1 + kInputSamples> down_work{};
    std::copy(down_history_.begin(), down_history_.end(), down_work.begin());
    for (int i = 0; i < kInputSamples; ++i) {
        down_work[kTaps - 1 + i] = frame[i] / 32768.0f;
    }

    std::array<float, kModelSamples> narrow_in{};
    for (int i = 0; i < kModelSamples; ++i) {
        const int at = kTaps - 1 + 3 * i;
        float sum = 0.0f;
        for (int k = 0; k < kTaps; ++k) sum += taps_[k] * down_work[at - k];
        narrow_in[i] = sum;
    }
    std::copy(down_work.begin() + kInputSamples, down_work.end(), down_history_.begin());

    std::array<float, kModelSamples> narrow_out{};
    const int result = mezon_ns_process_frame_float(engine_, narrow_in.data(), narrow_out.data());
    if (result != 0) return result;

    std::array<float, kPhaseTaps - 1 + kModelSamples> up_work{};
    std::copy(up_history_.begin(), up_history_.end(), up_work.begin());
    std::copy(narrow_out.begin(), narrow_out.end(), up_work.begin() + kPhaseTaps - 1);
    for (int i = 0; i < kModelSamples; ++i) {
        const int at = kPhaseTaps - 1 + i;
        for (int phase = 0; phase < 3; ++phase) {
            float value = 0.0f;
            for (int k = 0; k < kPhaseTaps; ++k) {
                value += taps_[3 * k + phase] * up_work[at - k];
            }
            const float pcm = std::round(value * 3.0f * 32768.0f);
            if (!std::isfinite(pcm)) return -2;
            frame[3 * i + phase] = static_cast<int16_t>(std::clamp(pcm, -32768.0f, 32767.0f));
        }
    }
    std::copy(up_work.begin() + kModelSamples, up_work.end(), up_history_.begin());
    return 0;
}
