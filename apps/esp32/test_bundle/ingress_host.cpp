#include "MeshIngress.h"
#include "MeshBundle.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <cstdlib>
using namespace mesh::ingress;
static unsigned checks = 0;
#define CHECK(x) do { ++checks; if (!(x)) { std::cerr << "FAIL " << __LINE__ << ": " << #x << '\n'; std::abort(); } } while (0)
static std::vector<uint8_t> load(const std::string& path) {
    std::ifstream f(path,std::ios::binary); CHECK(bool(f)); return {(std::istreambuf_iterator<char>(f)),{}};
}
static std::string text(const std::string& path) { auto b = load(path); return std::string(b.begin(),b.end()); }
static void codecTests() {
    for (const std::string& bad : {std::string("{\"a\":1,\"\\u0061\":2}"),std::string("1.0"),std::string("-0"),std::string("01"),std::string("9007199254740992"),
         std::string("{\"a\":1}garbage"),std::string("[1,]"),std::string("\"\\ud800\""),std::string("\"\xff\"",3)}) {
        Document d; CHECK(!json(bad,d));
    }
    for (const std::vector<uint8_t>& bad : {std::vector<uint8_t>{0x18,1},std::vector<uint8_t>{0x9f,0xff},std::vector<uint8_t>{0xa2,0x61,'a',0,0x61,'a',1},
         std::vector<uint8_t>{0xa2,0x61,'b',0,0x61,'a',1},std::vector<uint8_t>{0xf9,0,0},std::vector<uint8_t>{0xc0,0},std::vector<uint8_t>{0,0}}) {
        Document d; CHECK(!decode(bad,d));
    }
    Document d; CHECK(json("{\"z\":true,\"a\":[1,null,\"\\ud83c\\udf10\"]}",d));
    std::vector<uint8_t> b; CHECK(encode(d,d.root,b)); Document round; CHECK(decode(b,round));
    CHECK(fetchSubscription(true) != fetchSubscription(false));
    Document priorityQuery, normalQuery;
    CHECK(json(fetchRequest(true),priorityQuery)); CHECK(json(fetchRequest(false),normalQuery));
    const auto& priorityCells = priorityQuery.at(priorityQuery.root).children;
    const auto& normalCells = normalQuery.at(normalQuery.root).children;
    CHECK(priorityQuery.at(priorityCells[0]).s == "MOSIMO-REQ"); CHECK(normalQuery.at(normalCells[0]).s == "REQ");
    CHECK(priorityQuery.fields(priorityCells[2],{"order","filter"}));
    CHECK(priorityQuery.str(priorityCells[2],"order") == "priority-v1");
    CHECK(normalQuery.fields(normalCells[2],{"kinds","#t","limit"}));
    Authorization a,c; a.id = "a"; c.id = "b"; a.priority = 3; c.priority = 2; CHECK(higher(a,c));
    c.priority = 3; a.created = c.created = 1; CHECK(higher(a,c));
    c.created = 2; CHECK(higher(c,a));
}
int main(int argc,char** argv) {
    if (argc == 1) { codecTests(); std::cout << "PASS ingress codec/order " << checks << " assertions\n"; return 0; }
    int positional = argc; uint64_t minSequence = 1;
    if (argc >= 11 && std::string(argv[argc - 2]) == "--min-sequence") { minSequence = std::stoull(argv[argc - 1]); positional -= 2; }
    CHECK(positional == 9 || positional == 10);
    const std::string mode = argv[1]; Policy p; p.enabled = true; p.trustedClock = std::string(argv[8]) == "1";
    if (!configure(p,argv[2],argv[3],argv[4],minSequence)) return 1;
    if (mode == "configure") return 0;
    if (mode == "floor") p.minSequence = 11;
    if (mode == "disabled") p.enabled = false;
    uint64_t now = std::stoull(argv[7]); State state; Snapshot s;
    if (mode == "observe") {
        state.snapshot = load(argv[5]);
        if (!currentSnapshot(p,now,state,s,false)) return 1;
        auto original = state.snapshot;
        bool accepted = observeSnapshot(load(argv[6]),p,now,state,s);
        if (!accepted) { CHECK(state.snapshot == original); return 1; }
        std::vector<uint8_t> disk; CHECK(encodeState(state,p,disk)); State restored;
        CHECK(decodeState(disk,p,restored)); CHECK(restored.snapshot == state.snapshot);
        disk.back() ^= 1; CHECK(!decodeState(disk,p,restored));
        std::cout << s.sequence << '\n'; return 0;
    }
    if (!observeSnapshot(load(argv[5]),p,now,state,s)) return 1;
    Document event; if (!json(text(argv[6]),event)) return 1;
    Authorization a; std::vector<uint8_t> payload; std::string reason;
    if (!authorize(event,event.root,p,now,state,a,payload,reason)) {
        CHECK(payload.empty() && a.priority <= 1); std::cerr << reason << '\n'; return 1;
    }
    CHECK(a.authorSignature && a.binding && a.chainScope && a.certificateTime && a.revocation == Authorization::Fresh);
    CHECK(eligibleNow(p,now,a)); CHECK(!eligibleNow(p,a.validUntil,a)); CHECK(!eligibleNow(p,a.notAfter,a)); CHECK(!eligibleNow(p,a.deadline,a));
    CHECK(state.serials.size() == 1);
    std::vector<uint8_t> disk; CHECK(encodeState(state,p,disk)); State restored; CHECK(decodeState(disk,p,restored));
    CHECK(restored.serials.size() == 1 && restored.serials[0].certId == a.leafId);
    State full = state;
    for (unsigned i = 0; i < 15; ++i) {
        Serial extra; extra.serial = std::string(30,'0') + "a" + "0123456789abcde"[i]; extra.certId = a.leafId;
        full.serials.push_back(extra);
    }
    CHECK(encodeState(full,p,disk)); CHECK(decodeState(disk,p,restored)); CHECK(restored.serials.size() == 16);
    full.serials.push_back(full.serials[0]); CHECK(!encodeState(full,p,disk));
    if (positional == 10 && mode == "successive") {
        CHECK(encodeState(state,p,disk)); State persisted; CHECK(decodeState(disk,p,persisted));
        Document next; CHECK(json(text(argv[9]),next)); Authorization second; std::vector<uint8_t> secondPayload;
        CHECK(authorize(next,next.root,p,now,persisted,second,secondPayload,reason));
        CHECK(second.id != a.id && second.rootId == a.rootId && second.leafId == a.leafId);
        CHECK(second.sequence == s.sequence && persisted.serials.size() == 1 && !persisted.serials[0].conflict);
        CHECK(encodeState(persisted,p,disk)); CHECK(decodeState(disk,p,restored));
        std::cout << "PASS successive real-server events with persisted snapshot/serial state\n"; return 0;
    }
    if (positional == 10) {
        Document conflict; CHECK(json(text(argv[9]),conflict)); Authorization other; std::vector<uint8_t> rejected;
        CHECK(!authorize(conflict,conflict.root,p,now,state,other,rejected,reason)); CHECK(state.serials[0].conflict);
        CHECK(encodeState(state,p,disk)); CHECK(decodeState(disk,p,restored)); CHECK(restored.serials[0].conflict);
        CHECK(!authorize(event,event.root,p,now,restored,other,rejected,reason));
        std::cout << "PASS sticky serial conflict\n"; return 0;
    }
    std::vector<uint8_t> bundle; mesh::BundleInfo info; std::string error, originalJson;
    CHECK(mesh::wrapEventPayload(payload,1000,bundle,info,error)); CHECK(info.id == a.id && info.hop == 0 && info.age == 0);
    CHECK(mesh::exportEvent(bundle.data(),bundle.size(),info,originalJson,error));
    std::cout << originalJson << '\n' << a.priority << ' ' << a.rootId << ' ' << a.leafId << ' ' << s.hash << '\n';
    std::cout << hex(payload.data(),payload.size()) << '\n';
    return 0;
}
