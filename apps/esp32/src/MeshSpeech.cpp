#include "MeshSpeech.h"
#include <Arduino.h>
#include <M5Unified.h>
#include <aquestalk.h>
#include <LocalAquesTalkConfig.h>
#include <esp_partition.h>
#include <mbedtls/sha256.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {
constexpr size_t DictionaryBase = 0x10001000;
constexpr uint32_t MaxSamples = 8000 * 60;
const uint8_t* dictionary = nullptr;
spi_flash_mmap_handle_t dictionaryHandle;
bool dictionaryReadFailed = false;
mesh::Speech* owner = nullptr;

void error(const char* stage, int code) {
    Serial.printf("TTS error stage=%s code=%d\n", stage, code);
}

bool mapDictionary() {
    static_assert(AQ_DICT_BYTES == 1969776, "Unexpected dictionary payload size");
    const auto* partition = esp_partition_find_first(ESP_PARTITION_TYPE_DATA,
        static_cast<esp_partition_subtype_t>(0x40), "aqdic");
    if (!partition || partition->address != 0x400000 || partition->size != 0x200000 ||
        AQ_DICT_BYTES > partition->size || std::strlen(AQ_DICT_SHA256) != 64) {
        error("dictionary_partition", -1); return false;
    }
    const void* mapped = nullptr;
    const int rc = esp_partition_mmap(partition, 0, AQ_DICT_BYTES, SPI_FLASH_MMAP_DATA,
                                    &mapped, &dictionaryHandle);
    if (rc != ESP_OK) { error("dictionary_mmap", rc); return false; }
    uint8_t digest[32];
    // Hash only the raw payload, not the erased padding in the reserved partition.
    mbedtls_sha256_context sha;
    mbedtls_sha256_init(&sha);
    int status = mbedtls_sha256_starts_ret(&sha, 0);
    const auto* bytes = static_cast<const uint8_t*>(mapped);
    for (size_t at = 0; status == 0 && at < AQ_DICT_BYTES; at += 4096) {
        const size_t n = (AQ_DICT_BYTES - at < 4096) ? AQ_DICT_BYTES - at : 4096;
        status = mbedtls_sha256_update_ret(&sha, bytes + at, n);
        vTaskDelay(1);
    }
    if (!status) status = mbedtls_sha256_finish_ret(&sha, digest);
    mbedtls_sha256_free(&sha);
    char hex[65];
    if (!status) {
        for (size_t i = 0; i < 32; ++i) std::snprintf(hex + i * 2, 3, "%02x", digest[i]);
        if (std::strcmp(hex, AQ_DICT_SHA256)) status = -1;
    }
    if (status) {
        spi_flash_munmap(dictionaryHandle);
        error("dictionary_sha256", status); return false;
    }
    dictionary = bytes; dictionaryReadFailed = false;
    return true;
}
}

extern "C" size_t aqdic_open() { return dictionary ? DictionaryBase : 0; }
extern "C" void aqdic_close() { /* Mapping is owned by Speech, not the SDK. */ }
extern "C" size_t aqdic_read(size_t pos, size_t size, void* buf) {
    if (!dictionary || !buf || pos < DictionaryBase ||
        pos - DictionaryBase > AQ_DICT_BYTES || size > AQ_DICT_BYTES - (pos - DictionaryBase)) {
        dictionaryReadFailed = true; return 0;
    }
    std::memcpy(buf, dictionary + (pos - DictionaryBase), size);
    return size;
}

namespace mesh {
bool Speech::begin() {
    if (ready_.load()) return true;
    if (owner) { error("owner", -1); return false; }
    owner = this;
    k2rWork_ = static_cast<uint8_t*>(std::malloc(SIZE_AQK2R_MIN_WORK_BUF));
    synthWork_ = static_cast<uint32_t*>(std::malloc(AQ_SIZE_WORKBUF * sizeof(uint32_t)));
    bool created = false;
    bool mapped = false;
    do {
        if (!k2rWork_ || !synthWork_) { error("malloc", -1); break; }
        if (!mapDictionary()) break;
        mapped = true;
        const int rc = CAqK2R_Create(k2rWork_, SIZE_AQK2R_MIN_WORK_BUF);
        created = (rc == 0);
        if (rc || dictionaryReadFailed) { error("k2r_create", rc ? rc : -1); break; }
        const int synth = CAqTkPicoF_Init(synthWork_, 320, nullptr);
        if (synth) { error("synth_init", synth); break; }
        if (!M5.Speaker.begin()) { error("speaker_begin", -1); break; }
        if (xTaskCreate(taskEntry, "mesh-tts", 16384, this, 1, &task_) != pdPASS) {
            error("task_create", -1); break;
        }
        ready_.store(true);
        Serial.printf("TTS ready evaluation=1 dictionary_bytes=%u\n", unsigned(AQ_DICT_BYTES));
        return true;
    } while (false);
    if (created) CAqK2R_Release();
    if (mapped) { dictionary = nullptr; spi_flash_munmap(dictionaryHandle); }
    std::free(k2rWork_); k2rWork_ = nullptr;
    std::free(synthWork_); synthWork_ = nullptr;
    owner = nullptr;
    return false;
}

bool Speech::submit(const std::string& id, const std::string& text, bool local) {
    if (!ready_.load()) { error("not_ready", -1); return false; }
    portENTER_CRITICAL(&lock_);
    const auto result = inbox_.push(id, text, local);
    portEXIT_CRITICAL(&lock_);
    if (result != speech::Inbox::Queued) {
        error("enqueue", static_cast<int>(result)); return false;
    }
    Serial.printf("TTS queued id=%s\n", id.c_str());
    xTaskNotifyGive(task_);
    return true;
}
bool Speech::enqueue(const std::string& id, const std::string& text) { return submit(id, text, false); }
bool Speech::test() {
    char id[65];
    std::snprintf(id, sizeof(id), "local-test-%lu", static_cast<unsigned long>(++testId_));
    return submit(id, "音声読み上げの評価テストです。避難所の情報を確認してください。", true);
}
bool Speech::toggleMuted() {
    portENTER_CRITICAL(&lock_);
    const bool value = inbox_.toggle();
    portEXIT_CRITICAL(&lock_);
    Serial.printf("TTS mute=%d pending_cleared=1\n", value);
    if (task_) xTaskNotifyGive(task_);
    return value;
}
bool Speech::muted() const { return inbox_.isMuted.load(); }

void Speech::taskEntry(void* self) { static_cast<Speech*>(self)->run(); }
void Speech::run() {
    speech::Job job;
    for (;;) {
        bool have;
        portENTER_CRITICAL(&lock_);
        have = inbox_.pop(job);
        portEXIT_CRITICAL(&lock_);
        if (have && ready_.load()) {
            if (!inbox_.cancelled(job)) speak(job);
            else Serial.printf("TTS cancel id=%s\n", job.id);
        } else ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(100));
        vTaskDelay(1);
    }
}

bool Speech::waitOutput(const speech::Job& job, size_t limit, uint32_t started) {
    const uint32_t wait = millis();
    while (M5.Speaker.isPlaying(1) >= limit) {
        if (inbox_.cancelled(job)) return false;
        if (millis() - started >= 60000) { error("duration", -1); return false; }
        if (millis() - wait >= 1000) { error("output_timeout", -1); return false; }
        vTaskDelay(1);
    }
    if (inbox_.cancelled(job)) return false;
    if (millis() - started >= 60000) { error("duration", -1); return false; }
    return true;
}
bool Speech::stopOutput() {
    M5.Speaker.stop(1);
    const uint32_t started = millis();
    while (M5.Speaker.isPlaying(1)) {
        if (millis() - started >= 1000) {
            // Do not ever reuse buffers if the consumer cannot relinquish them.
            ready_.store(false); error("stop_timeout", -1); return false;
        }
        vTaskDelay(1);
    }
    return true;
}

void Speech::speak(const speech::Job& job) {
    Serial.printf("TTS start id=%s\n", job.id);
    const uint32_t started = millis();
    uint32_t samples = 0, nonzero = 0, chunks = 0;
    size_t at = 0;
    char text[401], roman[2048];
    bool ok = true;
    while (ok && speech::nextChunk(job.text, std::strlen(job.text), at, text)) {
        if (inbox_.cancelled(job)) { ok = false; break; }
        dictionaryReadFailed = false;
        std::memset(roman, 0, sizeof(roman));
        int code = CAqK2R_Convert(text, roman, sizeof(roman));
        vTaskDelay(1);
        if (code || dictionaryReadFailed || !std::memchr(roman, 0, sizeof(roman))) {
            error("convert", code ? code : -1); ok = false; break;
        }
        if (inbox_.cancelled(job)) { ok = false; break; }
        code = CAqTkPicoF_SetKoe(reinterpret_cast<const uint8_t*>(roman), 100, 0xffff);
        if (code) { error("set_koe", code); ok = false; break; }
        for (;;) {
            // Three rotating buffers; wait BEFORE refilling, not merely before
            // playRaw. Only this worker ever produces/stops on channel 1.
            if (!waitOutput(job, 2, started)) { ok = false; break; }
            int16_t* pcm = pcm_[buffer_];
            uint16_t count = 0;
            code = CAqTkPicoF_SyntheFrame(pcm, &count);
            if (code > 1 || count > 320 || (code == 0 && count == 0)) {
                error("synth_frame", code > 1 ? code : -1); ok = false; break;
            }
            if (inbox_.cancelled(job)) { ok = false; break; }
            if (count) {
                if (samples + count > MaxSamples || millis() - started >= 60000) {
                    error("duration", -1); ok = false; break;
                }
                if (!M5.Speaker.playRaw(pcm, count, 8000, false, 1, 1, false)) {
                    error("play_raw", -1); ok = false; break;
                }
                samples += count; ++chunks;
                for (uint16_t i = 0; i < count; ++i) if (pcm[i]) ++nonzero;
                buffer_ = (buffer_ + 1) % 3;
            }
            vTaskDelay(1);
            if (code == 1) break;
        }
    }
    if (ok) ok = waitOutput(job, 1, started);
    if (!ok) {
        stopOutput();
        Serial.printf("TTS %s id=%s samples=%lu nonzero=%lu chunks=%lu\n",
            inbox_.cancelled(job) ? "cancel" : "aborted", job.id,
            static_cast<unsigned long>(samples), static_cast<unsigned long>(nonzero),
            static_cast<unsigned long>(chunks));
        return;
    }
    if (!samples || !nonzero) { error("empty_audio", -1); return; }
    Serial.printf("TTS done id=%s samples=%lu nonzero=%lu chunks=%lu\n", job.id,
        static_cast<unsigned long>(samples), static_cast<unsigned long>(nonzero),
        static_cast<unsigned long>(chunks));
}
} // namespace mesh
