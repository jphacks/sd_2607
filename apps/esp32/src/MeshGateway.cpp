#include "MeshGateway.h"
#include "MeshRelay.h"

#ifdef MESH_GATEWAY_TTS
#include "MeshBle.h"
#include "MeshSpeech.h"
#endif

// Default builds remain offline. Local header is ignored, never provisioned over BLE.
#if defined(MESH_GATEWAY_BUILD) && defined(MESH_GATEWAY_CONFIG_HEADER)
#include MESH_GATEWAY_CONFIG_HEADER
#define MESH_GATEWAY_CONFIGURED 1
#elif defined(MESH_GATEWAY_BUILD) && __has_include("../private/gateway/config.h")
#include "../private/gateway/config.h"
#define MESH_GATEWAY_CONFIGURED 1
#endif

#ifndef MESH_GATEWAY_CONFIGURED
#ifdef MESH_GATEWAY_BUILD
#include <Arduino.h>
#endif
namespace mesh {
void gatewayBegin(Relay& relay) {
#ifdef MESH_GATEWAY_BUILD
    relay.enableGateway(); // Missing local config must not discard gateway custody.
    Serial.println("gateway: no local config; Internet off, pending data pinned");
#else
    (void)relay;
#endif
}
void gatewayPoll(Relay&, Ble*) {
#ifdef MESH_GATEWAY_TTS
    Speech::allowResources(true); // No Wi-Fi worker in the unconfigured offline fallback.
#endif
}
void gatewayWorkPoll() {}
bool gatewayNetworkBusy() { return false; }
}
#else
#include "GatewayProtocol.h"
#include "GatewayWire.h"
#include <ArduinoJson.h>
#include <mbedtls/sha1.h>
#include <mbedtls/base64.h>
#include <esp_system.h>
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>

#include <esp_timer.h>
#include <time.h>
#include <new>


namespace mesh {
namespace {
#ifdef MESH_GATEWAY_TTS
// One shared worker, no concurrent relay parsing or SDK allocation during TLS.
constexpr unsigned NetworkHeap = 80000;
// ESP-IDF mbedTLS has separate 16 KiB record buffers plus framing overhead;
// requiring one 32 KiB hole falsely stalls after TTS/BLE even when both fit.
constexpr unsigned TlsBlock = 18000;
#else
constexpr unsigned NetworkHeap = 110000;
constexpr unsigned TlsBlock = 32768;
#endif
struct Job {
    std::string id;
    std::vector<uint8_t> bytes;
    bool delivered = false;
};
struct Retry { std::string id; unsigned failures = 0; uint64_t at = 0; };
QueueHandle_t workQueue = nullptr, resultQueue = nullptr;
bool busy = false, started = false;
size_t cursor = 0;
std::vector<Retry> retries;
uint64_t nextWifi = 0;
uint64_t nowMs() { return esp_timer_get_time() / 1000; }

struct Socket : WiFiClientSecure {
    uint64_t now() { return nowMs(); }
    void pause() { vTaskDelay(pdMS_TO_TICKS(10)); }
};
struct Body { std::string bytes; };
using Wire = gateway::Wire<Socket>;
int request(const std::string& url, const std::string* operation, Body& body,
            const char* ca = MESH_GATEWAY_PLC_CA) {
    const size_t slash = url.find('/', 8);
    const std::string host = url.substr(8,slash == std::string::npos ? slash : slash - 8);
    const std::string path = slash == std::string::npos ? "/" : url.substr(slash);
    Socket tls;
    tls.setCACert(ca); tls.setHandshakeTimeout(10); tls.setTimeout(5);
    if (!tls.connect(host.c_str(), 443, 10000)) {
        char error[160]{}; const int code = tls.lastError(error, sizeof(error));
        Serial.printf("gateway: HTTPS TLS failed code=%d detail=%s heap=%u max_alloc=%u\n", code, error, ESP.getFreeHeap(), ESP.getMaxAllocHeap());
        return -1;
    }
    Serial.printf("gateway: HTTPS TLS verified heap=%u max_alloc=%u min_heap=%u\n", ESP.getFreeHeap(), ESP.getMaxAllocHeap(), ESP.getMinFreeHeap());
    Wire wire(tls, nowMs() + 15000);
    std::string headers = std::string(operation ? "POST " : "GET ") + path +
        " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\nCache-Control: no-cache\r\n";
    if (operation) headers += "Content-Type: application/json\r\nContent-Length: " + std::to_string(operation->size()) + "\r\n";
    Wire::Headers response;
    if (!wire.write(headers + "\r\n") || (operation && !wire.write(*operation)) || !wire.headers(response)) return -1;
    if (!operation && response.status >= 200 && response.status < 300 && !wire.body(response, body.bytes, 16384)) return -1;
    return response.status; // Redirects are never followed.
}
bool registerPlc(const std::string& operation) {
    if (operation.empty() || operation.size() > 8192 || !std::strlen(MESH_GATEWAY_PLC_URL)) return false;
    gateway::JsonLimit limit;
    JsonDocument expected(&limit);
    if (deserializeJson(expected, operation, DeserializationOption::NestingLimit(8))) return false;
    std::vector<uint8_t> encoded;
    if (!gateway::genesis(expected.as<JsonVariantConst>(), encoded)) return false;
    const std::string did = gateway::plcDid(encoded);
    if (did.empty()) return false;
    const std::string target = std::string(MESH_GATEWAY_PLC_URL) + "/" + did;
    Body log;
    int code = request(target + "/log", nullptr, log);
    if (code == 404) {
        Body ignored;
        // Only submit on an authenticated 404; never overwrite existing logs.
        request(target, &operation, ignored);
        log.bytes.clear();
        code = request(target + "/log", nullptr, log);
    }
    if (code < 200 || code >= 300) return false;
    JsonDocument observed(&limit);
    if (deserializeJson(observed, log.bytes, DeserializationOption::NestingLimit(8))) return false;
    auto history = observed.as<JsonArrayConst>();
    std::vector<uint8_t> first;
    // Historical confirmation ONLY. The directory validates PLC ECDSA signatures;
    // this gateway never asserts current key/PDS authority or PDS reconstruction.
    return history.size() > 0 && history.size() <= 128 && gateway::genesis(history[0], first) && first == encoded;
}
std::string b64(const uint8_t* bytes, size_t n) {
    uint8_t out[64]; size_t size = 0;
    if (mbedtls_base64_encode(out, sizeof(out), &size, bytes, n)) return {};
    return std::string(reinterpret_cast<char*>(out), size);
}
bool connectRelay(Socket& tls, Wire& wire) {
    tls.setCACert(MESH_GATEWAY_RELAY_CA); tls.setHandshakeTimeout(5); tls.setTimeout(5);
    if (!tls.connect(MESH_GATEWAY_RELAY_HOST, MESH_GATEWAY_RELAY_PORT, 5000)) {
        Serial.printf("gateway: relay TLS failed heap=%u max_alloc=%u\n", ESP.getFreeHeap(), ESP.getMaxAllocHeap());
        return false;
    }
    Serial.printf("gateway: relay TLS verified heap=%u max_alloc=%u min_heap=%u\n", ESP.getFreeHeap(), ESP.getMaxAllocHeap(), ESP.getMinFreeHeap());
    wire.resetDeadline(nowMs() + 20000);
    uint8_t nonce[16]; esp_fill_random(nonce, sizeof(nonce));
    const std::string key = b64(nonce, sizeof(nonce));
    const std::string challenge = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    uint8_t digest[20];
    if (mbedtls_sha1_ret(reinterpret_cast<const uint8_t*>(challenge.data()), challenge.size(), digest)) return false;
    const std::string request = std::string("GET ") + MESH_GATEWAY_RELAY_PATH + " HTTP/1.1\r\nHost: " +
        MESH_GATEWAY_RELAY_HOST + ":" + std::to_string(MESH_GATEWAY_RELAY_PORT) +
        "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + key + "\r\n\r\n";
    Wire::Headers h;
    if (!wire.write(request) || !wire.headers(h) || h.status != 101 || h.upgrade != "websocket" ||
        !Wire::token(h.connection, "upgrade") || h.accept != b64(digest, sizeof(digest))) return false;
    return true;
}
bool upload(const std::string& id, std::string& json) {
    Socket tls; Wire wire(tls,nowMs() + 20000);
    if (!connectRelay(tls,wire)) return false;
    json.insert(0, "[\"EVENT\","); json += "]";
    if (!wire.masked(1, reinterpret_cast<const uint8_t*>(json.data()), json.size(), esp_random())) return false;
    // Bounded frame count as well as bytes/deadline; no subscriptions or AUTH.
    for (unsigned count = 0; count < 32; ++count) {
        unsigned opcode; std::string payload;
        if (!wire.frame(opcode, payload) || opcode == 8) return false;
        if (opcode == 1 && gateway::relayOk(reinterpret_cast<const uint8_t*>(payload.data()), payload.size(), id)) return true;
        if (opcode == 9 && !wire.masked(10, reinterpret_cast<const uint8_t*>(payload.data()), payload.size(), esp_random())) return false;
    }
    return false;
}
void workOnce(TickType_t wait) {
        Job* job = nullptr;
        if (!workQueue || xQueueReceive(workQueue, &job, wait) != pdTRUE) return;

        Serial.printf("gateway: job start heap=%u max_alloc=%u min_heap=%u stack_free=%u\n", ESP.getFreeHeap(), ESP.getMaxAllocHeap(), ESP.getMinFreeHeap(), uxTaskGetStackHighWaterMark(nullptr));
        BundleInfo info; std::string json, error;
        // Leave room for a concurrent BLE frame and codec temporaries. Estimate
        // worst-case JSON escaping before allocating, not after an OOM/reset.
        const size_t jsonReserve = std::min(size_t(66000), job->bytes.size() * 6 + 512);
        const size_t reserve = jsonReserve + std::min(size_t(16384), job->bytes.size()) + 40000;
        if (WiFi.status() == WL_CONNECTED && time(nullptr) >= 1792281600 &&
            ESP.getFreeHeap() >= reserve && ESP.getMaxAllocHeap() >= jsonReserve &&
            exportEvent(job->bytes.data(), job->bytes.size(), info, json, error) && info.id == job->id) {
            // Release raw bundle before TLS allocations. Original is still durable in Relay.
            std::vector<uint8_t>().swap(job->bytes);
            // pds §6: PLC genesis control events go to the directory, never the relay.
            if (info.kind == 8173 && info.topic == "mosimo.jisaku.dev/plc") { std::string().swap(json); job->delivered = registerPlc(info.text); }
            else if (info.kind == 1 || info.kind == 5 || (info.kind == 8173 && info.topic == "mosimo.jisaku.dev/revocation")) {
                std::string().swap(info.text);
                job->delivered = upload(info.id, json);
            }
        }
        xQueueSend(resultQueue, &job, portMAX_DELAY);
}
#ifndef MESH_GATEWAY_TTS
void worker(void*) { for (;;) workOnce(portMAX_DELAY); }
#endif
bool configValid() {
    const std::string host = MESH_GATEWAY_RELAY_HOST, path = MESH_GATEWAY_RELAY_PATH;
    const std::string plc = MESH_GATEWAY_PLC_URL;
    if (!std::strlen(MESH_GATEWAY_WIFI_SSID) || std::strlen(MESH_GATEWAY_WIFI_SSID) > 32 ||
        std::strlen(MESH_GATEWAY_WIFI_PASSWORD) > 63 || host.empty() || host.size() > 253 ||
        host.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-") != std::string::npos ||
        path.empty() || path[0] != '/' || path.size() > 256 || path.find_first_of("\r\n ") != std::string::npos ||
        !std::strlen(MESH_GATEWAY_RELAY_CA) || MESH_GATEWAY_RELAY_PORT < 1 || MESH_GATEWAY_RELAY_PORT > 65535) return false;
    if (!plc.empty() && (plc.compare(0, 8, "https://") != 0 || plc.back() == '/' ||
        plc.find_first_of("\r\n @?#") != std::string::npos || plc.size() > 256 || !std::strlen(MESH_GATEWAY_PLC_CA))) return false;
    if (!plc.empty()) {
        const std::string plcHost = plc.substr(8, plc.find('/', 8) == std::string::npos ? std::string::npos : plc.find('/', 8) - 8);
        if (plcHost.empty() || plcHost.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-") != std::string::npos) return false;
    }
    return true;
}
}
bool gatewayNetworkBusy() { return busy; }
void gatewayWorkPoll() {
#ifdef MESH_GATEWAY_TTS
    workOnce(0);
#endif
}
void gatewayBegin(Relay& relay) {
    relay.enableGateway(); // Fail closed: invalid configuration must not unpin data.
    if (!configValid()) { Serial.println("gateway: invalid config; pending data pinned"); return; }
    workQueue = xQueueCreate(1, sizeof(Job*));
    resultQueue = xQueueCreate(1, sizeof(Job*));
    bool workerReady = workQueue && resultQueue;
#ifdef MESH_GATEWAY_TTS
    workerReady = workerReady && Speech::workerAvailable();
#else
    workerReady = workerReady && xTaskCreate(worker, "mesh-internet", 16384, nullptr, 1, nullptr) == pdPASS;
#endif
    if (!workerReady) {
        if (workQueue) vQueueDelete(workQueue);
        if (resultQueue) vQueueDelete(resultQueue);
        workQueue = resultQueue = nullptr;
        Serial.println("gateway: worker unavailable; pending data pinned"); return;
    }
    WiFi.persistent(false);
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(MESH_GATEWAY_WIFI_SSID, MESH_GATEWAY_WIFI_PASSWORD);
    configTime(0, 0, MESH_GATEWAY_NTP_SERVER);
    started = true; nextWifi = nowMs() + 30000;
    Serial.println("gateway: enabled; TLS clock sync required, public export opted in");
}
void gatewayPoll(Relay& relay, Ble* ble) {
#ifndef MESH_GATEWAY_TTS
    (void)ble;
#endif
    if (!started) {
#ifdef MESH_GATEWAY_TTS
        Speech::allowResources(true);
#endif
        return;
    }
    Job* finished = nullptr;
    const uint64_t now = nowMs();
    static uint64_t nextNetworkAt = 0;
#ifdef MESH_GATEWAY_TTS
    static bool resumePending = false;
#endif
    auto resumeRadio = [&]() {
#ifdef MESH_GATEWAY_TTS
        resumePending = ble && !ble->pauseForNetwork(false, relay);
        if (!resumePending) nextNetworkAt = now + 5000;
#endif
    };
#ifdef MESH_GATEWAY_TTS
    if (!Speech::resourcesBusy()) {
        Speech::allowResources(false);
        if (resumePending && !busy) { resumeRadio(); if (resumePending) return; }
    }
#endif
    if (xQueueReceive(resultQueue, &finished, 0) == pdTRUE) {
        busy = false;
#ifdef MESH_GATEWAY_TTS
        resumePending = ble != nullptr;
        nextNetworkAt = now + 5000; // Leave a BLE acquisition window between jobs.
#endif
        if (finished) for (auto& retry : retries) if (retry.id == finished->id) {
            if (finished->delivered) relay.gatewayDelivered(finished->id);
            else { retry.at = now + gateway::retryDelay(retry.failures); if (retry.failures < 6) ++retry.failures; }
        }
        if (finished) {
            Serial.printf("gateway: %s %s\n", finished->id.c_str(),
                          finished->delivered ? "accepted (not PDS publication)" : "unconfirmed; retained for retry");
            delete finished;
        }
    }
    static uint64_t statusAt = 0;
    if (now >= statusAt) {
        statusAt = now + 30000;
        Serial.printf("gateway: wifi=%u clock=%u heap=%u pending=%u busy=%u utc_epoch=%llu max_alloc=%u min_heap=%u\n",
            unsigned(WiFi.status() == WL_CONNECTED), unsigned(time(nullptr) >= 1792281600),
            ESP.getFreeHeap(), unsigned(relay.gatewayPending().size()), unsigned(busy), static_cast<unsigned long long>(time(nullptr)), ESP.getMaxAllocHeap(), ESP.getMinFreeHeap());
    }
#ifdef MESH_GATEWAY_TTS
    if (Speech::resourcesBusy()) {
        resumePending = ble != nullptr;
        if (!busy && ble && ble->pauseForNetwork(true, relay)) Speech::allowResources(true);
        return;
    }
    if (resumePending && !busy) { resumeRadio(); if (resumePending) return; }
#endif
    if (WiFi.status() != WL_CONNECTED) {
        if (now >= nextWifi) { nextWifi = now + 30000; WiFi.reconnect(); }
        return;
    }
    if (busy || now < nextNetworkAt || time(nullptr) < 1792281600) return;
    auto memoryReady = [&](unsigned minimum) {
#ifdef MESH_GATEWAY_TTS
        if (ble && !ble->pauseForNetwork(true, relay)) return false;
#endif
        if (ESP.getFreeHeap() >= minimum && ESP.getMaxAllocHeap() >= TlsBlock) return true;
        Serial.printf("gateway: memory deferred before resume heap=%u max_alloc=%u\n", ESP.getFreeHeap(), ESP.getMaxAllocHeap());
        resumeRadio();
        nextNetworkAt = now + 30000;
        return false;
    };
    const auto pending = relay.gatewayPending();
    retries.erase(std::remove_if(retries.begin(), retries.end(), [&](const Retry& r) {
        return std::find(pending.begin(), pending.end(), r.id) == pending.end();
    }), retries.end());
    for (size_t offset = 0; offset < pending.size(); ++offset) {
        const size_t index = (cursor + offset) % pending.size();
        const auto& id = pending[index];
        auto it = std::find_if(retries.begin(), retries.end(), [&](const Retry& r) { return r.id == id; });
        if (it == retries.end()) { Retry r; r.id = id; retries.push_back(r); it = retries.end() - 1; }
        if (now < it->at) continue;
        if (!memoryReady(NetworkHeap)) return;
        Job* job = new (std::nothrow) Job;
        if (!job) { resumeRadio(); return; }
        job->id = id;
        if (!relay.gatewayRead(id, job->bytes)) { delete job; resumeRadio(); it->at = now + 30000; continue; }
        if (xQueueSend(workQueue, &job, 0) != pdTRUE) { delete job; resumeRadio(); return; }
        cursor = (index + 1) % pending.size();
        busy = true; return;
    }
}
}
#endif
