#include "MeshGateway.h"
#include "MeshRelay.h"

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
void gatewayPoll(Relay&) {}
bool gatewayRequestIngress() { return false; }
}
#else
#include "GatewayProtocol.h"
#include "GatewayWire.h"
#include "MeshIngress.h"
#include "IngressTrust.h"
#include <esp_sntp.h>
#include <atomic>
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
struct Job {
    std::string id;
    std::vector<uint8_t> bytes;
    bool delivered = false, fetch = false, checkpointExists = false, checkpointValid = false;
    std::vector<std::string> known;
    std::vector<std::array<uint8_t,8>> seenPrefixes;
    std::vector<uint8_t> checkpoint, payload;
    ingress::Authorization authorization;
    std::string reason;
};
struct Retry { std::string id; unsigned failures = 0; uint64_t at = 0; };
QueueHandle_t workQueue = nullptr, resultQueue = nullptr;
bool busy = false, started = false;
size_t cursor = 0;
std::vector<Retry> retries;
uint64_t nextWifi = 0;
ingress::Policy ingressPolicy;
bool ingressReady = false, fetchRequested = false, ingressStorageHealthy = true;
std::atomic<bool> ingressClockSynced{false};
std::atomic<uint32_t> ingressSyncSeconds{0};
uint64_t nowMs() { return esp_timer_get_time() / 1000; }
void timeSynced(struct timeval*) {
    ingressSyncSeconds.store(uint32_t(nowMs() / 1000)); ingressClockSynced.store(true);
}
bool ingressClockTrusted() {
    return ingressClockSynced.load() && uint32_t(uint32_t(nowMs() / 1000) - ingressSyncSeconds.load()) < 3600;
}

struct Socket : WiFiClientSecure {
    uint64_t now() { return nowMs(); }
    void pause() { vTaskDelay(pdMS_TO_TICKS(10)); }
};
struct Body { std::string bytes; };
using Wire = gateway::Wire<Socket>;
int request(const std::string& url, const std::string* operation, Body& body,
            const char* ca = MESH_GATEWAY_PLC_CA, bool cbor = false) {
    const size_t slash = url.find('/', 8);
    const std::string host = url.substr(8,slash == std::string::npos ? slash : slash - 8);
    const std::string path = slash == std::string::npos ? "/" : url.substr(slash);
    Socket tls;
    tls.setCACert(ca); tls.setHandshakeTimeout(5); tls.setTimeout(5);
    if (!tls.connect(host.c_str(), 443, 5000)) return -1;
    Wire wire(tls, nowMs() + 15000);
    std::string headers = std::string(operation ? "POST " : "GET ") + path +
        " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\nCache-Control: no-cache\r\n";
    if (cbor) headers += "Accept: application/cbor\r\n";
    if (operation) headers += "Content-Type: application/json\r\nContent-Length: " + std::to_string(operation->size()) + "\r\n";
    Wire::Headers response;
    if (!wire.write(headers + "\r\n") || (operation && !wire.write(*operation)) || !wire.headers(response)) return -1;
    if (!operation && response.status >= 200 && response.status < 300 &&
        ((cbor && Wire::trim(response.contentType.substr(0,response.contentType.find(';'))) != "application/cbor") || !wire.body(response, body.bytes,cbor ? ingress::SNAPSHOT_LIMIT : 16384))) return -1;
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
    if (!tls.connect(MESH_GATEWAY_RELAY_HOST, MESH_GATEWAY_RELAY_PORT, 5000)) return false;
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
bool fetchPass(Job& job, ingress::State& state, bool priorityRequest) {
    Socket tls; Wire wire(tls,nowMs() + 20000);
    if (!connectRelay(tls,wire)) return false;
    const std::string subscription = ingress::fetchSubscription(priorityRequest);
    const std::string query = ingress::fetchRequest(priorityRequest);
    if (!wire.masked(1,reinterpret_cast<const uint8_t*>(query.data()),query.size(),esp_random())) return false;
    wire.resetDeadline(nowMs() + (priorityRequest ? 8000 : 15000));
    unsigned events = 0;
    for (unsigned count = 0; count < 24; ++count) {
        unsigned opcode; std::string frame;
        if (!wire.frame(opcode,frame,ingress::JSON_LIMIT) || opcode == 8) return false;
        if (opcode == 9) { if (!wire.masked(10,reinterpret_cast<const uint8_t*>(frame.data()),frame.size(),esp_random())) return false; continue; }
        if (opcode != 1) continue;
        if (ESP.getFreeHeap() < 70000 || ESP.getMaxAllocHeap() < 16000) return false;
        ingress::Document message;
        if (!ingress::json(frame,message) || message.at(message.root).type != ingress::Value::Array) return false;
        const auto& cells = message.at(message.root).children;
        if (cells.size() < 2 || message.at(cells[0]).type != ingress::Value::Text || message.at(cells[1]).type != ingress::Value::Text || message.at(cells[1]).s != subscription) continue;
        const auto& verb = message.at(cells[0]).s;
        if (verb == "EOSE" && cells.size() == 2) {
            const std::string close = "[\"CLOSE\",\"" + subscription + "\"]";
            wire.masked(1,reinterpret_cast<const uint8_t*>(close.data()),close.size(),esp_random());
            return true;
        }
        if (verb == "CLOSED") return false;
        if (verb != "EVENT") continue;
        if (cells.size() != 3 || ++events > 8) return false;
        const std::string id = message.str(cells[2],"id");
        if (id.size() != 64) continue;
        if (std::find(job.known.begin(),job.known.end(),id) != job.known.end()) continue;
        const auto idBytes = ingress::unhex(id);
        bool seen = false;
        if (idBytes.size() == 32) for (const auto& prefix : job.seenPrefixes)
            if (std::equal(prefix.begin(),prefix.end(),idBytes.begin())) { seen = true; break; }
        if (seen) continue; // May suppress on a rare prefix collision; can NEVER authorize or ACK.
        if (ESP.getFreeHeap() < 65000 || ESP.getMaxAllocHeap() < 12000) return false;
        ingress::Authorization candidate; std::vector<uint8_t> payload; std::string reason;
        if (!ingress::authorize(message,cells[2],ingressPolicy,uint64_t(time(nullptr)),state,candidate,payload,reason)) {
            if (job.authorization.id.empty()) job.reason = reason;
        } else if (ingress::higher(candidate,job.authorization)) {
            job.authorization = std::move(candidate); job.payload = std::move(payload); job.reason.clear();
        }
    }
    return false;
}
void fetchIngress(Job& job) {
    if (!ingressReady || !ingressClockTrusted()) { job.reason = "ingress disabled/clock unknown"; return; }
    ingress::State state;
    if (job.checkpointExists && !ingress::decodeState(job.bytes,ingressPolicy,state)) { job.reason = "checkpoint invalid: operator recovery required"; return; }
    ingress::Snapshot old;
    if (!state.snapshot.empty() && ingress::currentSnapshot(ingressPolicy,uint64_t(time(nullptr)),state,old,false) &&
        std::binary_search(old.keys.begin(),old.keys.end(),ingressPolicy.rootPubkey)) { job.reason = "root revoked: no automatic recovery"; return; }
    Body body; ingress::Snapshot fresh;
    const int code = request(ingressPolicy.snapshotUrl,nullptr,body,ingressPolicy.snapshotCa.c_str(),true);
    if (!ingressClockTrusted()) { job.reason = "clock sync no longer fresh"; return; }
    std::vector<uint8_t> downloaded(body.bytes.begin(),body.bytes.end());
    if (code >= 200 && code < 300) {
        if (!ingress::observeSnapshot(downloaded,ingressPolicy,uint64_t(time(nullptr)),state,fresh)) { job.reason = "snapshot invalid/rollback/equivocation"; return; }
    } else if (!ingress::currentSnapshot(ingressPolicy,uint64_t(time(nullptr)),state,fresh)) { job.reason = "no fresh verified snapshot"; return; }
    // Release HTTP buffers before allocating TLS + event/certificate trees.
    std::string().swap(body.bytes); std::vector<uint8_t>().swap(downloaded); std::vector<uint8_t>().swap(job.bytes);
    if (std::binary_search(fresh.keys.begin(),fresh.keys.end(),ingressPolicy.rootPubkey)) job.reason = "root revoked";
    else {
        if (!fetchPass(job,state,true)) {
            job.authorization = {}; job.payload.clear();
            if (!fetchPass(job,state,false)) { job.authorization = {}; job.payload.clear(); job.reason = "fetch incomplete (priority and REQ fallback)"; }
        }
        for (const auto& serial : state.serials) if (serial.conflict && serial.certId == job.authorization.leafId) { job.payload.clear(); job.reason = "certificate serial conflict"; }
        if (!ingress::eligibleNow(ingressPolicy,uint64_t(time(nullptr)),job.authorization)) job.payload.clear();
    }
    job.checkpointValid = ingress::encodeState(state,ingressPolicy,job.checkpoint);
    if (job.reason.empty()) job.reason = job.payload.empty() ? "no new authorized candidate in bounded window" : "verified candidate";
}
void worker(void*) {
    for (;;) {
        Job* job = nullptr;
        if (xQueueReceive(workQueue, &job, portMAX_DELAY) != pdTRUE) continue;
        if (job->fetch) {
            fetchIngress(*job);
            xQueueSend(resultQueue,&job,portMAX_DELAY);
            continue;
        }
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
            if (info.kind == 8173) { std::string().swap(json); job->delivered = registerPlc(info.text); }
            else if (info.kind == 1 || info.kind == 5) {
                std::string().swap(info.text);
                job->delivered = upload(info.id, json);
            }
        }
        xQueueSend(resultQueue, &job, portMAX_DELAY);
    }
}
bool snapshotUrlValid(const std::string& url) {
    if (url.compare(0,8,"https://") || url.size() > 256 || url.find_first_of("\r\n\t @?#") != std::string::npos) return false;
    const size_t slash = url.find('/',8);
    const std::string host = url.substr(8,slash == std::string::npos ? slash : slash - 8);
    return !host.empty() && host.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-") == std::string::npos;
}
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
void gatewayBegin(Relay& relay) {
    relay.enableGateway(); // Fail closed: invalid configuration must not unpin data.
    if (!configValid()) { Serial.println("gateway: invalid config; pending data pinned"); return; }
    ingressPolicy.enabled = MESH_INGRESS_ENABLED;
    ingressPolicy.trustedClock = MESH_INGRESS_TRUST_CLOCK;
    ingressPolicy.snapshotUrl = MESH_INGRESS_SNAPSHOT_URL;
    ingressPolicy.snapshotCa = MESH_INGRESS_SNAPSHOT_CA;
    ingressReady = ingressPolicy.enabled && ingressPolicy.trustedClock && snapshotUrlValid(ingressPolicy.snapshotUrl) &&
        !ingressPolicy.snapshotCa.empty() && ingress::configure(ingressPolicy,MESH_INGRESS_ROOT_PUBKEY,MESH_INGRESS_SCOPE_CBOR_HEX,
            MESH_INGRESS_SELECTED_CBOR_HEX,MESH_INGRESS_MIN_SEQUENCE);
    Serial.println(ingressReady ? "ingress: configured, A requests priority download; waiting for SNTP" : "ingress: disabled/no valid public root+selection+clock opt-in");
    workQueue = xQueueCreate(1, sizeof(Job*));
    resultQueue = xQueueCreate(1, sizeof(Job*));
    if (!workQueue || !resultQueue || xTaskCreate(worker, "mesh-internet", 16384, nullptr, 1, nullptr) != pdPASS) {
        if (workQueue) vQueueDelete(workQueue);
        if (resultQueue) vQueueDelete(resultQueue);
        workQueue = resultQueue = nullptr;
        Serial.println("gateway: worker unavailable; pending data pinned"); return;
    }
    WiFi.persistent(false);
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(MESH_GATEWAY_WIFI_SSID, MESH_GATEWAY_WIFI_PASSWORD);
    sntp_set_time_sync_notification_cb(timeSynced);
    configTime(0, 0, MESH_GATEWAY_NTP_SERVER);
    started = true; nextWifi = nowMs() + 30000;
    Serial.println("gateway: enabled; TLS clock sync required, public export opted in");
}
bool gatewayRequestIngress() {
    if (!started || !ingressReady || !ingressStorageHealthy) return false;
    fetchRequested = true; return true;
}
void gatewayPoll(Relay& relay) {
    if (!started) return;
    Job* finished = nullptr;
    const uint64_t now = nowMs();
    if (xQueueReceive(resultQueue, &finished, 0) == pdTRUE) {
        busy = false;
        if (finished->fetch) {
            bool committed = finished->checkpointValid && ingressStorageHealthy;
            if (committed && !relay.ingressWriteState(finished->checkpoint)) {
                ingressStorageHealthy = false; committed = false;
                Serial.println("ingress: checkpoint save failed; ingress latched off, no advertise");
            }
            bool saved = false;
            auto permitted = [&]() { return ingressClockTrusted() && ingress::eligibleNow(ingressPolicy,uint64_t(time(nullptr)),finished->authorization); };
            if (committed && !finished->payload.empty() && permitted()) {
                std::vector<uint8_t> bundle; BundleInfo info; std::string error;
                const uint64_t life = std::min(MAX_RESIDENCE_MS,(finished->authorization.deadline - uint64_t(time(nullptr))) * 1000);
                if (life && wrapEventPayload(finished->payload,life,bundle,info,error) && permitted())
                    saved = relay.ingressSave(bundle,finished->authorization.id,finished->authorization.orgName,finished->authorization.priority,permitted);
            }
            Serial.printf("ingress: %s; %s\n",saved ? "persisted before advertise" : "not admitted",finished->reason.c_str());
            if (saved) Serial.printf("ingress: root=%s leaf=%s bot=%s type=%s snapshot=%llu issued=%llu until=%llu checked=%llu priority=%u org=%s importer=%s; authorSignature=1 binding=1 chainScope=1 certificateTime=1 revocation=fresh-as-of\n",
                finished->authorization.rootId.c_str(),finished->authorization.leafId.c_str(),
                finished->authorization.botDid.c_str(),finished->authorization.type.c_str(),
                static_cast<unsigned long long>(finished->authorization.sequence),static_cast<unsigned long long>(finished->authorization.issuedAt),
                static_cast<unsigned long long>(finished->authorization.validUntil),static_cast<unsigned long long>(finished->authorization.checkedAt),
                finished->authorization.priority,finished->authorization.orgId.c_str(),finished->authorization.orgName.c_str());
            delete finished; finished = nullptr;
        }
        if (finished) for (auto& retry : retries) if (retry.id == finished->id) {
            if (finished->delivered) relay.gatewayDelivered(finished->id);
            else { retry.at = now + gateway::retryDelay(retry.failures); if (retry.failures < 6) ++retry.failures; }
        }
        if (finished) {
            Serial.printf("gateway: %.8s %s\n", finished->id.c_str(),
                          finished->delivered ? "accepted (not PDS publication)" : "unconfirmed; retained for retry");
            delete finished;
        }
    }
    static uint64_t statusAt = 0;
    if (now >= statusAt) {
        statusAt = now + 30000;
        Serial.printf("gateway: wifi=%u clock=%u ingress_clock=%u heap=%u pending=%u busy=%u\n",
            unsigned(WiFi.status() == WL_CONNECTED), unsigned(time(nullptr) >= 1792281600), unsigned(ingressClockTrusted()),
            ESP.getFreeHeap(), unsigned(relay.gatewayPending().size()), unsigned(busy));
    }
    if (WiFi.status() != WL_CONNECTED) {
        if (now >= nextWifi) { nextWifi = now + 30000; WiFi.reconnect(); }
        return;
    }
    if (busy || time(nullptr) < 1792281600 || ESP.getFreeHeap() < 110000) return;
    if (fetchRequested && ingressReady && ingressStorageHealthy && ingressClockTrusted() && ESP.getFreeHeap() >= 130000) {
        Job* job = new (std::nothrow) Job;
        if (!job) return;
        job->fetch = true; job->known = relay.retainedIds(); job->seenPrefixes = relay.seenPrefixes();
        if (!relay.ingressReadState(job->bytes,job->checkpointExists)) {
            ingressStorageHealthy = false; delete job;
            Serial.println("ingress: checkpoint unreadable; latched off, no reset"); return;
        }
        if (xQueueSend(workQueue,&job,0) != pdTRUE) { delete job; return; }
        fetchRequested = false; busy = true; return;
    }
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
        Job* job = new (std::nothrow) Job;
        if (!job) return;
        job->id = id;
        if (!relay.gatewayRead(id, job->bytes)) { delete job; it->at = now + 30000; continue; }
        if (xQueueSend(workQueue, &job, 0) != pdTRUE) { delete job; return; }
        cursor = (index + 1) % pending.size();
        busy = true; return;
    }
}
}
#endif
