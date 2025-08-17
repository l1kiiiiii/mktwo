//
// Created by ailik on 11-08-2025.
//
// Self-contained C++ implementation for MFCC and DTW without external libraries.
// Uses standard C++11 <complex>, <vector>, <cmath>, <algorithm>.
// FFT is Cooley-Tukey radix-2 from cp-algorithms.com (self-contained).
// MFCC is a basic implementation: pre-emphasis, hamming window, FFT, mel filterbanks (hardcoded for 40 filters), log, DCT (simple cos-based).
// DTW is basic implementation with cosine distance.

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <complex>
#include <cmath>
#include <algorithm>

#define LOG_TAG "MantraMatcher"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using cd = std::complex<double>;
const double PI = acos(-1.0);
const int SAMPLE_RATE = 48000;

// Self-contained FFT (Cooley-Tukey radix-2, bit-reversal)
void fft(std::vector<cd>& a, bool invert) {
    int n = a.size();
    int lg_n = 0;
    while ((1 << lg_n) < n) lg_n++;

    for (int i = 0; i < n; i++) {
        int rev = 0;
        for (int j = 0; j < lg_n; j++) {
            if (i & (1 << j)) rev |= (1 << (lg_n - 1 - j));
        }
        if (i < rev) std::swap(a[i], a[rev]);
    }

    for (int len = 2; len <= n; len <<= 1) {
        double ang = 2 * PI / len * (invert ? -1 : 1);
        cd wlen(std::cos(ang), std::sin(ang));
        for (int i = 0; i < n; i += len) {
            cd w(1);
            for (int j = 0; j < len / 2; j++) {
                cd u = a[i + j], v = a[i + j + len / 2] * w;
                a[i + j] = u + v;
                a[i + j + len / 2] = u - v;
                w *= wlen;
            }
        }
    }

    if (invert) {
        for (cd& x : a) x /= n;
    }
}

// Power spectrum from FFT
std::vector<double> power_spectrum(const std::vector<float>& frame) {
    int n = frame.size();
    int fft_size = 1;
    while (fft_size < n) fft_size <<= 1;
    std::vector<cd> fft_input(fft_size, 0.0);
    for (int i = 0; i < n; i++) fft_input[i] = frame[i];
    fft(fft_input, false);
    std::vector<double> power(fft_size / 2 + 1);
    for (int i = 0; i <= fft_size / 2; i++) {
        power[i] = std::norm(fft_input[i]) / fft_size;
    }
    return power;
}

// Pre-emphasis
void pre_emphasis(std::vector<float>& signal) {
    for (size_t i = signal.size() - 1; i > 0; --i) {
        signal[i] -= 0.95f * signal[i - 1];
    }
}

// Hamming window
void hamming_window(std::vector<float>& frame) {
    int n = frame.size();
    for (int i = 0; i < n; i++) {
        frame[i] *= 0.54 - 0.46 * std::cos(2 * PI * i / (n - 1));
    }
}

// Mel frequency conversion
double hz_to_mel(double hz) {
    return 2595.0 * std::log10(1.0 + hz / 700.0);
}

double mel_to_hz(double mel) {
    return 700.0 * (std::pow(10.0, mel / 2595.0) - 1.0);
}

// Create mel filterbanks (40 filters for 48kHz, 13 MFCCs)
std::vector<std::vector<double>> create_mel_filterbanks(int num_filters, int fft_size, int sample_rate) {
    double low_freq_mel = 0.0;
    double high_freq_mel = hz_to_mel(sample_rate / 2.0);
    std::vector<double> mel_points(num_filters + 2);
    for (int i = 0; i < num_filters + 2; i++) {
        mel_points[i] = low_freq_mel + (high_freq_mel - low_freq_mel) * i / (num_filters + 1);
    }
    std::vector<double> hz_points(num_filters + 2);
    for (int i = 0; i < num_filters + 2; i++) {
        hz_points[i] = mel_to_hz(mel_points[i]);
    }
    std::vector<int> bin(num_filters + 2);
    for (int i = 0; i < num_filters + 2; i++) {
        bin[i] = static_cast<int>(std::floor((fft_size + 1) * hz_points[i] / sample_rate));
    }
    std::vector<std::vector<double>> filters(num_filters, std::vector<double>(fft_size / 2 + 1, 0.0));
    for (int m = 1; m <= num_filters; m++) {
        for (int k = bin[m - 1]; k < bin[m]; k++) {
            filters[m - 1][k] = (k - bin[m - 1]) * 1.0 / (bin[m] - bin[m - 1]);
        }
        for (int k = bin[m]; k < bin[m + 1]; k++) {
            filters[m - 1][k] = (bin[m + 1] - k) * 1.0 / (bin[m + 1] - bin[m]);
        }
    }
    return filters;
}

// Apply mel filters
std::vector<double> apply_mel_filters(const std::vector<double>& power, const std::vector<std::vector<double>>& filterbanks) {
    int num_filters = filterbanks.size();
    std::vector<double> mel_energies(num_filters, 0.0);
    for (int m = 0; m < num_filters; m++) {
        for (size_t k = 0; k < power.size(); k++) {
            mel_energies[m] += power[k] * filterbanks[m][k];
        }
        if (mel_energies[m] > 0) mel_energies[m] = std::log(mel_energies[m]);
        else mel_energies[m] = std::log(1e-10); // Avoid log(0)
    }
    return mel_energies;
}

// DCT for MFCC (simple cos-based, for 13 coefficients)
std::vector<float> dct(const std::vector<double>& mel_energies) {
    int num_mfcc = 13;
    int num_filters = mel_energies.size();
    std::vector<float> mfcc(num_mfcc, 0.0f);
    for (int k = 0; k < num_mfcc; k++) {
        double sum = 0.0;
        for (int m = 0; m < num_filters; m++) {
            sum += mel_energies[m] * std::cos(PI * k * (m + 0.5) / num_filters);
        }
        mfcc[k] = static_cast<float>(sum);
    }
    return mfcc;
}

// MFCC extraction for a frame (audioData is one frame, e.g., 2048 samples)
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mktwo_MainActivity_extractMFCC(JNIEnv* env, jobject /* this */, jfloatArray audioData) {
    jsize len = env->GetArrayLength(audioData);
    std::vector<float> frame(len);
    env->GetFloatArrayRegion(audioData, 0, len, frame.data());

    // Pre-emphasis
    pre_emphasis(frame);

    // Hamming window
    hamming_window(frame);

    // Power spectrum via FFT
    std::vector<double> power = power_spectrum(frame);

    // Mel filterbanks (hardcoded for 40 filters)
    std::vector<std::vector<double>> filterbanks = create_mel_filterbanks(40, power.size() * 2 - 2, SAMPLE_RATE); // fft_size = power.size() * 2 - 2

    // Apply filters and log
    std::vector<double> mel_energies = apply_mel_filters(power, filterbanks);

    // DCT to get 13 MFCCs
    std::vector<float> mfcc = dct(mel_energies);

    jfloatArray result = env->NewFloatArray(mfcc.size());
    env->SetFloatArrayRegion(result, 0, mfcc.size(), mfcc.data());
    return result;
}

// Cosine similarity for DTW
float cosineSimilarity(const std::vector<float>& vec1, const std::vector<float>& vec2) {
    if (vec1.size() != vec2.size()) return 0.0f;
    float dot = 0.0f, norm1 = 0.0f, norm2 = 0.0f;
    for (size_t i = 0; i < vec1.size(); ++i) {
        dot += vec1[i] * vec2[i];
        norm1 += vec1[i] * vec1[i];
        norm2 += vec2[i] * vec2[i];
    }
    float denom = std::sqrt(norm1) * std::sqrt(norm2);
    return denom == 0.0f ? 0.0f : dot / denom;
}

// DTW
extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_mktwo_MainActivity_computeDTW(JNIEnv* env, jobject /* this */, jobjectArray mfccSeq1, jobjectArray mfccSeq2) {
    jsize len1 = env->GetArrayLength(mfccSeq1);
    jsize len2 = env->GetArrayLength(mfccSeq2);
    std::vector<std::vector<float>> seq1(len1), seq2(len2);

    for (jsize i = 0; i < len1; ++i) {
        jfloatArray frame = (jfloatArray)env->GetObjectArrayElement(mfccSeq1, i);
        jsize frameLen = env->GetArrayLength(frame);
        seq1[i].resize(frameLen);
        env->GetFloatArrayRegion(frame, 0, frameLen, seq1[i].data());
        env->DeleteLocalRef(frame);
    }
    for (jsize i = 0; i < len2; ++i) {
        jfloatArray frame = (jfloatArray)env->GetObjectArrayElement(mfccSeq2, i);
        jsize frameLen = env->GetArrayLength(frame);
        seq2[i].resize(frameLen);
        env->GetFloatArrayRegion(frame, 0, frameLen, seq2[i].data());
        env->DeleteLocalRef(frame);
    }

    std::vector<std::vector<float>> dp(len1 + 1, std::vector<float>(len2 + 1, std::numeric_limits<float>::infinity()));
    dp[0][0] = 0.0f;
    for (size_t i = 1; i <= len1; ++i) {
        for (size_t j = 1; j <= len2; ++j) {
            float cost = 1.0f - cosineSimilarity(seq1[i - 1], seq2[j - 1]);
            dp[i][j] = cost + std::min({dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]});
        }
    }
    return 1.0f - (dp[len1][len2] / (len1 + len2));
}