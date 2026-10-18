#include "MeshBundle.h"
#include "GatewayProtocol.h"
#include "GatewayWire.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <cstdlib>

static unsigned checks = 0;
#define CHECK(x) do { ++checks; if (!(x)) { std::cerr << "FAIL " << __LINE__ << ": " << #x << '\n'; std::abort(); } } while (0)
struct Socket {
    std::string input, output;
    size_t pos = 0;
    uint64_t clock = 0;
    bool keepOpen = false, failWrite = false;
    uint64_t now() { return clock; }
    void pause() { clock += 10; }
    bool connected() { return keepOpen || pos < input.size(); }
    int available() { return static_cast<int>(input.size() - pos); }
    int read(uint8_t* p, size_t n) { n = std::min(n, input.size() - pos); std::memcpy(p, input.data() + pos, n); pos += n; return static_cast<int>(n); }
    size_t write(const uint8_t* p, size_t n) { if (failWrite) return 0; output.append(reinterpret_cast<const char*>(p), n); return n; }
};
using Wire = mesh::gateway::Wire<Socket>;
static void wireTests() {
    CHECK(Wire::token("keep-alive, upgrade", "upgrade"));
    CHECK(!Wire::token("not-upgrade", "upgrade"));
    {
        Socket s; s.input = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello";
        Wire w(s, 100); Wire::Headers h; std::string body;
        CHECK(w.headers(h)); CHECK(h.status == 200); CHECK(w.body(h, body)); CHECK(body == "hello");
    }
    {
        Socket s; s.input = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhe\r\n3;x=y\r\nllo\r\n0\r\nTrailer: yes\r\n\r\n";
        Wire w(s, 100); Wire::Headers h; std::string body;
        CHECK(w.headers(h) && w.body(h, body)); CHECK(body == "hello");
    }
    for (const std::string& headers : {
        std::string("HTTP/1.1 200 OK\r\nContent-Length: 16385\r\n\r\n"),
        std::string("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\n"),
        std::string("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n"),
        std::string("HTTP/1.1 200 OK\r\nX: ") + std::string(513, 'x') + "\r\n\r\n",
        std::string("HTTP/1.1 200 OK\n\n")}) {
        Socket s; s.input = headers; Wire w(s, 100); Wire::Headers h; CHECK(!w.headers(h));
    }
    {
        Socket s; s.keepOpen = true; Wire w(s, 25); Wire::Headers h; CHECK(!w.headers(h)); CHECK(s.clock == 30);
    }
    for (const std::string& chunks : {std::string("4001\r\n"), std::string("2\r\nx"), std::string("1\r\nxXX")}) {
        Socket s; s.input = chunks; Wire w(s, 100); Wire::Headers h; h.chunked = true;
        std::string body; CHECK(!w.body(h, body)); CHECK(body.size() <= 16384);
    }
    {
        Socket s; s.input = std::string(16385, 'x'); Wire w(s, 100); Wire::Headers h;
        std::string body; CHECK(!w.body(h, body)); CHECK(body.size() == 16384);
    }
    {
        Socket s; s.input = std::string("\x81\x02ok", 4); Wire w(s, 100); unsigned op; std::string payload;
        CHECK(w.frame(op, payload)); CHECK(op == 1 && payload == "ok");
    }
    for (const std::string& frame : {std::string("\x01\x00", 2), std::string("\xc1\x00", 2),
        std::string("\x81\x80", 2), std::string("\x81\x7e\x04\x01", 4),
        std::string("\x81\x7f", 2), std::string("\x89\x7e\x00\x7e", 4), std::string("\x82\x00", 2)}) {
        Socket s; s.input = frame; Wire w(s, 100); unsigned op; std::string payload; CHECK(!w.frame(op, payload));
    }
    {
        Socket s; Wire w(s, 100); std::string text(66000, 'x');
        CHECK(w.masked(1, reinterpret_cast<const uint8_t*>(text.data()), text.size(), 0x12345678));
        CHECK(s.output.size() == text.size() + 14);
        CHECK(static_cast<uint8_t>(s.output[1]) == 0xff);
        for (size_t i = 0; i < text.size(); ++i) CHECK((s.output[14 + i] ^ s.output[10 + i % 4]) == 'x');
        s.failWrite = true; CHECK(!w.write("failure"));
    }
    {
        Socket s; s.input = std::string("\x81\x7e\x18\x00",4) + std::string(6144,'x');
        Wire w(s,100); unsigned op; std::string payload;
        CHECK(w.frame(op,payload,6144)); CHECK(op==1&&payload.size()==6144);
    }
    {
        Socket s; s.input = std::string("\x81\x7e\x18\x01",4);
        Wire w(s,100); unsigned op; std::string payload; CHECK(!w.frame(op,payload,6144));
    }
    {
        Socket s; Wire w(s,100); Wire::Headers h; h.length=4097; std::string body;
        CHECK(!w.body(h,body,4096)); CHECK(body.empty());
    }
    const std::string id(64, 'a');
    auto ok = [&](const std::string& json) { return mesh::gateway::relayOk(reinterpret_cast<const uint8_t*>(json.data()), json.size(), id); };
    CHECK(ok("[\"OK\",\"" + id + "\",true,\"\"]"));
    CHECK(!ok("[\"OK\",\"" + id + "\",false,\"duplicate: ignored\"]"));
    CHECK(!ok("[\"OK\",\"" + std::string(64, 'b') + "\",true,\"\"]"));
    CHECK(!ok("[\"OK\",\"" + id + "\",1,\"\"]"));
    CHECK(!ok("[\"OK\",\"" + id + "\",\"true\",\"\"]"));
    CHECK(!ok("[\"NOTICE\",\"saved\"]"));
    CHECK(!ok(std::string(1025, 'x')));
    CHECK(mesh::gateway::retryDelay(0) == 5000);
    CHECK(mesh::gateway::retryDelay(1) == 10000);
    CHECK(mesh::gateway::retryDelay(99) == 300000);
}
int main(int argc, char** argv) {
    if (argc == 1) { wireTests(); std::cout << "PASS gateway wire/protocol " << checks << " assertions\n"; return 0; }
    if (argc != 3) return 2;
    std::ifstream f(argv[2], std::ios::binary); CHECK(bool(f));
    std::string input((std::istreambuf_iterator<char>(f)), {});
    if (std::string(argv[1]) == "plc") {
        mesh::gateway::JsonLimit limit; JsonDocument doc(&limit);
        std::vector<uint8_t> encoded;
        if (input.size() > 8192 || deserializeJson(doc, input, DeserializationOption::NestingLimit(8)) ||
            !mesh::gateway::genesis(doc.as<JsonVariantConst>(), encoded)) return 1;
        std::cout << mesh::gateway::plcDid(encoded) << '\n'; return 0;
    }
    mesh::BundleInfo info; std::string json = "stale", error;
    if (!mesh::exportEvent(reinterpret_cast<const uint8_t*>(input.data()), input.size(), info, json, error)) {
        CHECK(json.empty() && info.id.empty() && !error.empty()); return 1;
    }
    CHECK(error.empty()); std::cout << json << '\n'; return 0;
}
