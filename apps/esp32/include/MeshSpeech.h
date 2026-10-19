#pragma once
#include "SpeechText.h"
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

namespace mesh {
// One process-lifetime instance, owned/called by the main task. No boot speech.
// enqueue is for NEW validated posts only, not restored history. Accepted jobs
// cancel current speech and replace the single pending job; recent duplicates
// do neither. Muted submissions are rejected, never queued for unmute.
// SDK and channel 1 have exactly one worker owner; channel 0 is independent.
class Speech {
public:
    Speech() = default;
    Speech(const Speech&) = delete;
    Speech& operator=(const Speech&) = delete;
    bool begin();
    static bool workerAvailable();
    static bool resourcesBusy();
    static void allowResources(bool allowed);
    bool enqueue(const std::string& id, const std::string& text);
    bool test();
    bool toggleMuted();
    bool muted() const;
private:
    bool submit(const std::string& id, const std::string& text, bool local);
    static void taskEntry(void* self);
    void run();
    void speak(const speech::Job& job);
    bool waitOutput(const speech::Job& job, size_t limit, uint32_t started);
    bool stopOutput();
    speech::Inbox inbox_;
    portMUX_TYPE lock_ = portMUX_INITIALIZER_UNLOCKED;
    TaskHandle_t task_ = nullptr;
    std::atomic<bool> ready_{false}, resourcesAllowed_{false};
    bool active_ = false;
    uint8_t* k2rWork_ = nullptr;
    uint32_t* synthWork_ = nullptr;
    int16_t pcm_[3][320]{};
    size_t buffer_ = 0;
    uint32_t testId_ = 0;
};
} // namespace mesh
