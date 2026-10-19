// Boundary mocks live in this file; test_speech.py makes temporary header shims.
#ifndef SPEECH_MOCK_DEFINITIONS
#define SPEECH_MOCK_DEFINITIONS
#include <algorithm>
#include <cassert>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>
using TaskHandle_t = void*;
using portMUX_TYPE = int;
#define portMUX_INITIALIZER_UNLOCKED 0
#define portENTER_CRITICAL(x) ((void)(x))
#define portEXIT_CRITICAL(x) ((void)(x))
#define pdPASS 1
#define pdTRUE 1
#define pdMS_TO_TICKS(x) (x)
#define ESP_OK 0
#define ESP_PARTITION_TYPE_DATA 1
#define SPI_FLASH_MMAP_DATA 0
#define SIZE_AQK2R_MIN_WORK_BUF (20 * 1024)
#define AQ_SIZE_WORKBUF 100
#define AQ_DICT_BYTES 1969776
#define AQ_DICT_SHA256 "0000000000000000000000000000000000000000000000000000000000000000"
struct Idle {};
struct MockSerial {
    std::string log;
    void printf(const char* format, ...) {
        char line[512]; va_list args; va_start(args, format);
        std::vsnprintf(line, sizeof(line), format, args); va_end(args); log += line;
    }
};
extern MockSerial Serial;
extern uint32_t nowMs;
extern std::function<void()> onDelay, onConvert, onFrame;
extern void (*worker)(void*);
extern void* workerArg;
extern int createCode, convertCode, initCode, frameCode;
extern bool sdkActive;
extern size_t networkPolls;
extern bool badHash, taskFail, outputStuck, silent, playFail;
extern size_t hashed, frameIndex, frameCalls, stopCalls, notifications;
extern std::vector<std::string> converted;
struct MockSpeaker {
    struct Lease { const int16_t* data; std::vector<int16_t> snapshot; uint32_t until; };
    std::deque<Lease> leases;
    std::vector<const int16_t*> buffers;
    size_t queued = 0;
    uint32_t stopReleaseAt = 0, stoppedAt = 0;
    bool begin() { return true; }
    void check() const {
        for (const auto& l : leases) assert(std::equal(l.snapshot.begin(), l.snapshot.end(), l.data));
    }
    size_t isPlaying(uint8_t channel) {
        assert(channel == 1); check();
        if (!outputStuck) {
            if (stopReleaseAt && nowMs >= stopReleaseAt) { leases.clear(); stopReleaseAt = 0; }
            while (!leases.empty() && nowMs >= leases.front().until) leases.pop_front();
        }
        return leases.size();
    }
    bool playRaw(const int16_t* pcm, size_t n, uint32_t hz, bool stereo, uint32_t repeat, int ch, bool cut) {
        assert(hz == 8000 && !stereo && repeat == 1 && ch == 1 && !cut);
        assert(n && n <= 320 && isPlaying(1) < 2);
        if (playFail) return false;
        for (const auto& l : leases) assert(l.data != pcm);
        leases.push_back({pcm, {pcm, pcm + n}, (leases.empty() ? nowMs : leases.back().until) + 40});
        queued += n; buffers.push_back(pcm); return true;
    }
    void stop(uint8_t ch) {
        assert(ch == 1); ++stopCalls; stoppedAt = nowMs;
        // Retain references after stop returns, as the real playback task can.
        if (!leases.empty()) stopReleaseAt = nowMs + 3;
    }
};
struct MockM5 { MockSpeaker Speaker; };
extern MockM5 M5;
inline uint32_t millis() { return nowMs; }
inline void vTaskDelay(unsigned ticks) {
    M5.Speaker.check(); nowMs += ticks;
    if (onDelay) onDelay();
}
inline int xTaskCreate(void (*fn)(void*), const char*, unsigned stack, void* arg, unsigned priority, TaskHandle_t* handle) {
    assert(stack >= 12288 && stack <= 16384 && priority == 1);
    if (taskFail) return 0;
    worker = fn; workerArg = arg; *handle = arg; return pdPASS;
}
inline void xTaskNotifyGive(TaskHandle_t) { ++notifications; }
inline unsigned ulTaskNotifyTake(int, unsigned) { throw Idle{}; }
using esp_partition_subtype_t = int;
using spi_flash_mmap_handle_t = unsigned;
struct esp_partition_t { uint32_t address = 0x400000, size = 0x200000; };
extern esp_partition_t partition;
extern std::vector<uint8_t> dict;
inline const esp_partition_t* esp_partition_find_first(int type, int subtype, const char* label) {
    assert(type == 1 && subtype == 0x40 && std::string(label) == "aqdic"); return &partition;
}
inline int esp_partition_mmap(const esp_partition_t*, size_t offset, size_t n, int, const void** mapped, unsigned* handle) {
    assert(offset == 0 && n == AQ_DICT_BYTES); *mapped = dict.data(); *handle = 1; return 0;
}
inline void spi_flash_munmap(unsigned) {}
struct mbedtls_sha256_context {};
inline void mbedtls_sha256_init(mbedtls_sha256_context*) { hashed = 0; }
inline int mbedtls_sha256_starts_ret(mbedtls_sha256_context*, int is224) { assert(!is224); return 0; }
inline int mbedtls_sha256_update_ret(mbedtls_sha256_context*, const uint8_t* p, size_t n) {
    assert(p == dict.data() + hashed && n <= 4096); hashed += n; return 0;
}
inline int mbedtls_sha256_finish_ret(mbedtls_sha256_context*, uint8_t* digest) {
    assert(hashed == AQ_DICT_BYTES); std::memset(digest, badHash ? 1 : 0, 32); return 0;
}
inline void mbedtls_sha256_free(mbedtls_sha256_context*) {}
extern "C" size_t aqdic_open();
extern "C" void aqdic_close();
extern "C" size_t aqdic_read(size_t, size_t, void*);
inline int CAqK2R_Create(uint8_t* work, int size) {
    assert(work && size == SIZE_AQK2R_MIN_WORK_BUF && aqdic_open() && !(aqdic_open() & 3));
    sdkActive = !createCode; return createCode;
}
inline void CAqK2R_Release() { sdkActive = false; aqdic_close(); }
inline int CAqTkPicoF_Init(uint32_t* work, uint16_t count, const char* key) {
    assert(work && count == 320 && key == nullptr); return initCode;
}
inline int CAqK2R_Convert(const char* text, char* roman, int size) {
    assert(std::strlen(text) <= 400 && size == 2048);
    converted.emplace_back(text);
    if (onConvert) onConvert();
    std::strcpy(roman, "mock-private-roman"); return convertCode;
}
inline int CAqTkPicoF_SetKoe(const uint8_t*, uint16_t speed, uint16_t pause) {
    assert(speed == 100 && pause == 0xffff); frameIndex = 0; return 0;
}
inline uint8_t CAqTkPicoF_SyntheFrame(short* pcm, uint16_t* count) {
    ++frameCalls;
    if (onFrame) onFrame();
    if (frameCode) return static_cast<uint8_t>(frameCode);
    if (frameIndex++ == 5) { *count = 7; std::fill(pcm, pcm + 7, silent ? 0 : 17); return 1; }
    *count = 320; std::fill(pcm, pcm + 320, silent ? 0 : int(frameIndex)); return 0;
}
#endif

#ifndef SPEECH_MOCK_HEADER
#include "MeshSpeech.h"

#include <iostream>
bool sdkActive = false;
size_t networkPolls = 0;
namespace mesh { void gatewayWorkPoll() { assert(!sdkActive); ++networkPolls; } }
MockSerial Serial;
MockM5 M5;
uint32_t nowMs = 0;
std::function<void()> onDelay, onConvert, onFrame;
void (*worker)(void*) = nullptr;
void* workerArg = nullptr;
int createCode = 0, convertCode = 0, initCode = 0, frameCode = 0;
bool badHash = false, taskFail = false, outputStuck = false, silent = false, playFail = false;
size_t hashed = 0, frameIndex = 0, frameCalls = 0, stopCalls = 0, notifications = 0;
esp_partition_t partition;
std::vector<uint8_t> dict(AQ_DICT_BYTES, 0x35);
std::vector<std::string> converted;

static std::string id(unsigned n) { char out[65]; std::snprintf(out, sizeof(out), "%064x", n); return out; }
static void pump() { try { worker(workerArg); } catch (const Idle&) {} }
static void resetOutput() {
    Serial.log.clear(); M5.Speaker = MockSpeaker{}; converted.clear();
    onDelay = onConvert = onFrame = nullptr;
    convertCode = frameCode = 0; outputStuck = silent = playFail = false;
}
static void chunks() {
    const std::vector<std::string> texts = {
        "避難所です。情報を確認！安全ですか？", std::string(2000, 'a'),
        std::string(99, 'x') + "😀。あ", "漢字かなカナ\xF0\xA0\x80\x80",
        std::string(100, '.') + "次。"};
    for (const auto& s : texts) {
        char clean[2001], part[401]; assert(mesh::speech::sanitize(s, clean));
        size_t at = 0; std::string joined;
        while (mesh::speech::nextChunk(clean, std::strlen(clean), at, part)) {
            size_t p = 0, points = 0;
            while (p < std::strlen(part)) {
                uint32_t cp; const auto n = mesh::speech::decode(part + p, std::strlen(part) - p, cp);
                assert(n); p += n; ++points;
            }
            assert(points > 0 && points <= 100 && std::strlen(part) <= 400); joined += part;
        }
        assert(joined == s && at == s.size());
    }
    char out[2001];
    for (const auto& s : std::vector<std::string>{"\xc0\x80", "\xed\xa0\x80", "\xf4\x90\x80\x80", "\xe3\x81", "\x80", std::string(2001, 'a'), "", "\n\t"})
        assert(!mesh::speech::sanitize(s, out));
    assert(mesh::speech::sanitize(std::string("あ\0\n\t", 6) + "\xc2\x85\xe2\x80\xaeい", out));
    assert(std::string(out) == "あ     い");
    // Exercise every boundary in mixed 1/2/3/4-byte text without losing bytes.
    for (size_t len = 1; len <= 500; ++len) {
        std::string s;
        for (size_t i = 0; i < len; ++i) s += (i % 4 == 0 ? "a" : i % 4 == 1 ? "é" : i % 4 == 2 ? "日" : "😀");
        assert(mesh::speech::sanitize(s, out)); size_t at = 0; std::string joined; char part[401];
        while (mesh::speech::nextChunk(out, std::strlen(out), at, part)) joined += part;
        assert(joined == s);
    }
}
static void inbox() {
    mesh::speech::Inbox box; mesh::speech::Job job;
    assert(box.push(id(1), "\xff") == mesh::speech::Inbox::Invalid);
    assert(box.push(id(1), "一") == mesh::speech::Inbox::Queued);
    assert(box.push(id(1), "再送") == mesh::speech::Inbox::Duplicate);
    for (unsigned i = 2; i <= 4; ++i) assert(box.push(id(i), "新着") == mesh::speech::Inbox::Queued);
    assert(box.pop(job) && job.id == id(4));
    assert(!box.pop(job));
    const auto generation = job.generation;
    assert(box.push(id(4), "重複") == mesh::speech::Inbox::Duplicate);
    assert(box.push(id(71), "\xff") == mesh::speech::Inbox::Invalid);
    assert(!box.cancelled(job) && box.generation.load() == generation);
    assert(box.push(id(70), "消去される保留") == mesh::speech::Inbox::Queued);
    assert(box.toggle()); assert(box.cancelled(job) && !box.pop(job));
    assert(box.push(id(5), "ミュート") == mesh::speech::Inbox::Muted);
    assert(!box.toggle()); assert(box.cancelled(job));
    assert(box.push(id(5), "解除") == mesh::speech::Inbox::Queued);
    assert(box.push(id(1), "削除済みも再読しない") == mesh::speech::Inbox::Duplicate);
    for (unsigned i = 6; i <= 69; ++i) box.push(id(i), "更新");
    assert(box.push(id(1), "64件後は再受付") == mesh::speech::Inbox::Queued);
    assert(box.push("local-test-1", "試験", true) == mesh::speech::Inbox::Queued);
}
static void latestWins(mesh::Speech& speech) {
    // Arrivals before the worker runs replace, rather than accumulate.
    resetOutput();
    for (unsigned n = 100; n <= 110; ++n) assert(speech.enqueue(id(n), "最新"));
    assert(!speech.enqueue(id(105), "置き換え済みの重複"));
    assert(!speech.enqueue(id(110), "保留中の重複"));
    pump();
    assert(converted == std::vector<std::string>{"最新"});
    assert(Serial.log.find("TTS start id=" + id(110)) != std::string::npos);
    assert(Serial.log.find("TTS cancel") == std::string::npos);
    const auto frames = frameCalls;
    assert(!speech.enqueue(id(110), "完了後の重複")); pump();
    assert(frameCalls == frames);

    // Duplicate and invalid arrivals during playback leave the current job alone.
    resetOutput(); assert(speech.enqueue(id(111), "継続"));
    onDelay = [&] {
        if (M5.Speaker.leases.size() != 2) return;
        assert(!speech.enqueue(id(111), "重複"));
        assert(!speech.enqueue(id(112), "\xff"));
        assert(!speech.enqueue("invalid", "不正ID"));
        onDelay = nullptr;
    };
    const auto stops = stopCalls;
    pump();
    assert(stopCalls == stops && Serial.log.find("TTS done id=" + id(111)) != std::string::npos);
    assert(Serial.log.find("TTS cancel") == std::string::npos);

    // New arrivals while converting/synthesizing cancel before old PCM is queued.
    for (unsigned n : {112u, 114u}) {
        resetOutput(); assert(speech.enqueue(id(n), "旧"));
        auto replace = [&, n] {
            assert(speech.enqueue(id(n + 1), "新"));
            onConvert = onFrame = nullptr;
        };
        if (n == 112) onConvert = replace; else onFrame = replace;
        pump();
        assert(M5.Speaker.queued == 1607);
        assert(Serial.log.find("TTS cancel id=" + id(n)) != std::string::npos);
        assert(Serial.log.find("TTS done id=" + id(n + 1)) != std::string::npos);
    }

    // Cancel with two leased buffers. Further arrivals while stop is pending
    // replace the pending message without touching the old buffer references.
    resetOutput(); assert(speech.enqueue(id(116), std::string(250, 'a')));
    uint32_t interruptedAt = 0;
    const auto beforeStop = stopCalls;
    onDelay = [&] {
        if (!interruptedAt && M5.Speaker.leases.size() == 2) {
            interruptedAt = nowMs;
            assert(speech.enqueue(id(117), "中間"));
        } else if (M5.Speaker.stopReleaseAt) {
            assert(speech.enqueue(id(118), "最新の本文"));
            assert(!speech.enqueue(id(117), "中間の再送"));
            assert(!speech.enqueue(id(116), "古い再送"));
            onDelay = nullptr;
        }
    };
    pump();
    assert(interruptedAt && stopCalls == beforeStop + 1 && M5.Speaker.leases.empty());
    assert(M5.Speaker.stoppedAt - interruptedAt <= 1);
    assert(converted == (std::vector<std::string>{std::string(100, 'a'), "最新の本文"}));
    assert(Serial.log.find("TTS start id=" + id(117)) == std::string::npos);
    assert(Serial.log.find("TTS done id=" + id(118)) != std::string::npos);
    assert(!speech.enqueue(id(116), "中断後の重複"));

    // Stop at the first text-chunk boundary, never convert remaining chunks.
    resetOutput(); assert(speech.enqueue(id(119), std::string(250, 'b')));
    onDelay = [&] {
        if (frameIndex != 6) return;
        assert(speech.enqueue(id(120), "次の投稿")); onDelay = nullptr;
    };
    pump();
    assert(converted == (std::vector<std::string>{std::string(100, 'b'), "次の投稿"}));
    assert(Serial.log.find("TTS cancel id=" + id(119)) != std::string::npos);
    assert(Serial.log.find("TTS done id=" + id(120)) != std::string::npos);

    // Replacement during final output drain also stops the old output.
    resetOutput(); assert(speech.enqueue(id(123), "終端待ち"));
    onDelay = [&] {
        if (frameIndex != 6 || M5.Speaker.leases.size() != 1) return;
        assert(speech.enqueue(id(124), "終端で割り込み")); onDelay = nullptr;
    };
    const auto beforeDrain = stopCalls;
    pump();
    assert(stopCalls == beforeDrain + 1);
    assert(Serial.log.find("TTS cancel id=" + id(123)) != std::string::npos);
    assert(Serial.log.find("TTS done id=" + id(123)) == std::string::npos);
    assert(Serial.log.find("TTS done id=" + id(124)) != std::string::npos);

    // Muting discards pending/latest and unmuting never resurrects it.
    resetOutput(); assert(speech.enqueue(id(121), "破棄"));
    assert(speech.toggleMuted()); assert(!speech.enqueue(id(122), "ミュート中"));
    assert(!speech.toggleMuted()); pump();
    assert(converted.empty() && M5.Speaker.queued == 0);
    assert(!speech.enqueue(id(121), "破棄済みの重複"));
}
int main() {
    chunks(); inbox();
    static mesh::Speech speech;
    assert(!speech.enqueue(id(1), "未初期化"));
    partition.address = 0; assert(!speech.begin()); partition.address = 0x400000;
    badHash = true; assert(!speech.begin()); badHash = false;
#ifndef MESH_GATEWAY_TTS
    createCode = 200; assert(!speech.begin()); createCode = 0;
    initCode = 121; assert(!speech.begin()); initCode = 0;
#endif
    taskFail = true; assert(!speech.begin()); taskFail = false;
    assert(speech.begin()); assert(speech.begin());
    assert(Serial.log.find("TTS ready evaluation=1 dictionary_bytes=1969776") != std::string::npos);
    pump(); assert(frameCalls == 0); // Never speak at boot.
#ifdef MESH_GATEWAY_TTS
    assert(!mesh::Speech::resourcesBusy());
    assert(speech.enqueue(id(899), "許可待ち"));
    pump(); assert(frameCalls == 0 && mesh::Speech::resourcesBusy());
    mesh::Speech::allowResources(true); pump(); assert(!mesh::Speech::resourcesBusy());
    resetOutput();
    assert(speech.enqueue(id(900), "初期化失敗")); createCode = 200; pump(); createCode = 0;
    assert(Serial.log.find("stage=lazy_init code=200") != std::string::npos);
    assert(speech.enqueue(id(901), "合成初期化失敗")); initCode = 121; pump(); initCode = 0;
    assert(Serial.log.find("stage=lazy_init code=121") != std::string::npos);
    assert(speech.enqueue(id(902), "単一ワーカー")); pump();
    assert(networkPolls > 0 && !sdkActive);
#endif
    char bytes[4]; const auto base = aqdic_open();
    assert(base != 0 && !(base & 3));
    assert(aqdic_read(base, 4, bytes) == 4 && bytes[0] == 0x35);
    assert(aqdic_read(base + AQ_DICT_BYTES - 4, 4, bytes) == 4);
    assert(aqdic_read(base - 1, 1, bytes) == 0);
    assert(aqdic_read(base + AQ_DICT_BYTES - 1, 2, bytes) == 0);
    assert(aqdic_read(SIZE_MAX, 4, bytes) == 0);
    resetOutput(); assert(speech.test()); pump();
    assert(Serial.log.find("TTS done id=local-test-1") != std::string::npos);
    assert(M5.Speaker.queued > 0 && M5.Speaker.buffers.size() > 3);
    for (size_t i = 3; i < M5.Speaker.buffers.size(); ++i) assert(M5.Speaker.buffers[i] == M5.Speaker.buffers[i - 3]);
    assert(Serial.log.find("mock-private-roman") == std::string::npos);
    resetOutput(); assert(speech.enqueue(id(10), "本文をログに出さない")); pump();
    assert(Serial.log.find("samples=1607 nonzero=1607 chunks=6") != std::string::npos);
    assert(Serial.log.find("本文") == std::string::npos);
    assert(!speech.enqueue(id(10), "重複"));
    resetOutput(); assert(speech.enqueue(id(11), "変換エラー")); convertCode = 103; pump();
    assert(Serial.log.find("stage=convert code=103") != std::string::npos && M5.Speaker.queued == 0);
    resetOutput(); assert(speech.enqueue(id(12), "途中停止"));
    onFrame = [&] { speech.toggleMuted(); speech.toggleMuted(); onFrame = nullptr; };
    pump(); assert(Serial.log.find("TTS cancel") != std::string::npos && M5.Speaker.queued == 0);
    resetOutput(); assert(speech.enqueue(id(13), "変換中停止"));
    onConvert = [&] { speech.toggleMuted(); onConvert = nullptr; }; pump();
    assert(speech.muted() && M5.Speaker.queued == 0); assert(!speech.enqueue(id(14), "保留しない"));
    speech.toggleMuted(); assert(speech.enqueue(id(14), "解除後")); pump();
    resetOutput(); assert(speech.test());
    const auto stopsBefore = stopCalls;
    uint32_t cancelledAt = 0;
    onDelay = [&] {
        if (M5.Speaker.leases.size() == 2) {
            cancelledAt = nowMs; speech.toggleMuted(); onDelay = nullptr;
        }
    };
    pump();
    assert(speech.muted() && cancelledAt && nowMs - cancelledAt < 5);
    assert(stopCalls == stopsBefore + 1 && M5.Speaker.leases.empty());
    assert(Serial.log.find("TTS cancel") != std::string::npos);
    assert(Serial.log.find("TTS done") == std::string::npos);
    speech.toggleMuted();
    latestWins(speech);
    resetOutput(); assert(speech.test()); silent = true; pump();
    assert(Serial.log.find("stage=empty_audio") != std::string::npos && Serial.log.find("TTS done") == std::string::npos);
    resetOutput(); assert(speech.test()); frameCode = 105; pump();
    assert(Serial.log.find("stage=synth_frame code=105") != std::string::npos);
    resetOutput(); assert(speech.test()); playFail = true; pump();
    assert(Serial.log.find("stage=play_raw") != std::string::npos && M5.Speaker.queued == 0);
    resetOutput(); assert(speech.test());
    onFrame = [&] { nowMs += 60000; onFrame = nullptr; }; pump();
    assert(Serial.log.find("stage=duration") != std::string::npos);
    resetOutput(); assert(speech.test()); outputStuck = true; pump();
    assert(Serial.log.find("stage=output_timeout") != std::string::npos);
    assert(Serial.log.find("stage=stop_timeout") != std::string::npos);
    assert(!speech.enqueue(id(15), "故障後"));
    std::cout << "speech host: UTF-8, latest-wins preemption/dedup/mute, dictionary bounds, real worker boundary checks passed\n";
}
#endif
