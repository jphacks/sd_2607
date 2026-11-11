// Run via test_relay.py: real MeshRelay/MeshBundle/MeshMessages/Negentropy and
// mbedTLS against a simulated peer speaking the mesh §5 CBOR messages, with a
// fake LittleFS/clock. No BLE or filesystem writes outside the mock.
#include "MeshRelay.h"
#include <LittleFS.h>

#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
MockFS LittleFS;
uint64_t fakeNow = 100000;
uint8_t nextRandom = 0x80; // Relay nonces start high unless a test lowers them.
static size_t checks = 0;
#define CHECK(x) do{++checks;if(!(x)){std::cerr<<"FAIL "<<__LINE__<<": "<<#x<<"\n";std::abort();}}while(0)
using mesh::msg::Id;
namespace msg = mesh::msg;

std::vector<uint8_t> load(const std::string& p){std::ifstream f(p,std::ios::binary);CHECK(bool(f));return {(std::istreambuf_iterator<char>(f)),{}};}
mesh::BundleInfo infoOf(const std::vector<uint8_t>& b){mesh::BundleInfo i;std::string err;CHECK(mesh::validateBundle(b.data(),b.size(),i,err));return i;}
std::string idOf(const std::vector<uint8_t>& b){return infoOf(b).id;}
Id idBytes(const std::string& h){Id id{};for(size_t i=0;i<32;++i)id[i]=static_cast<uint8_t>(std::stoul(h.substr(i*2,2),nullptr,16));return id;}
std::string hexId(const Id& id){return mesh::hex(id.data(),id.size());}

struct Env {
 mesh::Relay relay; bool allow=true;
 std::vector<std::vector<uint8_t>> out; std::vector<std::string> notes;
 std::vector<mesh::BundleInfo> received;
 uint64_t peerRequest=0, peerTransfer=0;
 unsigned relaySide=0;
 explicit Env(bool reset=true){
  if(reset){LittleFS=MockFS();fakeNow=100000;}
  CHECK(relay.begin([this](const uint8_t*p,size_t n){if(!allow)return false;out.emplace_back(p,p+n);return true;},
                    [this](const std::string&s){notes.push_back(s);},
                    [this](const mesh::BundleInfo&info){CHECK(LittleFS.renames>0);received.push_back(info);}));
  CHECK(!LittleFS.format);
 }
 void rx(const std::vector<uint8_t>& m){relay.receive(m.data(),m.size());}
 // Sends pending relay output and returns the decoded messages produced since `from`.
 std::vector<msg::Message> flush(size_t& from){
  for(int i=0;i<64;++i)relay.tick();
  std::vector<msg::Message> result;
  for(;from<out.size();++from){msg::Message m;std::string e;CHECK(msg::decode(out[from].data(),out[from].size(),m,e));result.push_back(m);}
  return result;
 }
 std::vector<msg::Message> flush(){size_t from=cursor;auto r=flush(from);cursor=from;return r;}
 size_t cursor=0;
 // HELLO exchange; peerLow makes the peer side 0 (relay answers reconciliation).
 void start(bool peerLow=true,uint64_t peerCredit=4096,uint64_t peerMessage=4096){
  out.clear();cursor=0;peerRequest=peerTransfer=0;
  relay.connected();auto first=flush();
  CHECK(first.size()==1&&first[0].type==msg::HELLO&&first[0].maxBundleBytes==24000&&first[0].maxMessageBytes==4096&&first[0].initialCredit==4096);
  uint8_t nonce[8];for(auto& b:nonce)b=peerLow?0x00:0xff;
  rx(msg::hello(nonce,65536,1,peerCredit,peerMessage));
  relaySide=peerLow?1:0;
  CHECK(relay.sessionActive());
 }
 std::vector<msg::Message> inventory(const std::vector<std::string>& ids){
  std::vector<Id> list;for(auto& i:ids)list.push_back(idBytes(i));
  rx(msg::ids(msg::INVENTORY,nullptr,list));return flush();
 }
 // Sends one bundle as the peer would after a relay WANT.
 std::vector<msg::Message> deliver(const msg::Message& want,const std::vector<uint8_t>& bundle,const std::string& id,size_t firstFragment=4000){
  const uint64_t t=peerTransfer++;
  const size_t first=std::min(firstFragment,bundle.size());
  rx(msg::begin(t,want.first,idBytes(id),bundle.size(),bundle.data(),first));
  auto replies=flush();
  for(size_t at=first;at<bundle.size();at+=4000){
   rx(msg::data(t,bundle.data()+at,std::min<size_t>(4000,bundle.size()-at)));
   auto more=flush();replies.insert(replies.end(),more.begin(),more.end());
  }
  return replies;
 }
 // Peer side of acquisition: offer, expect a WANT for exactly `id`, deliver it.
 std::vector<msg::Message> push(const std::vector<uint8_t>& bundle){
  const std::string id=idOf(bundle);
  auto replies=inventory({id});CHECK(replies.size()==1&&replies[0].type==msg::WANT&&replies[0].ids.size()==1&&hexId(replies[0].ids[0])==id);
  return deliver(replies[0],bundle,id);
 }
};
const msg::Message* find(const std::vector<msg::Message>& ms,uint64_t type){for(auto& m:ms)if(m.type==type)return &m;return nullptr;}
bool stored(const std::vector<msg::Message>& ms,unsigned side,uint64_t code=msg::STORED){
 const msg::Message* r=find(ms,msg::RESULT);return r&&r->targetKind==msg::TARGET_TRANSFER&&r->side==1-side&&r->code==code;
}
std::map<std::string,std::vector<uint8_t>> cacheSnapshot(){
 std::map<std::string,std::vector<uint8_t>> files;
 for(const auto& item:LittleFS.files)if(item.first.compare(0,6,"/mesh/")==0)files.insert(item);
 return files;
}
void seedFullCache(const std::string& dir){
 LittleFS.files.clear();
 for(int slot=0;slot<16;++slot){std::string name=std::to_string(slot);if(slot<10)name="0"+name;LittleFS.files["/mesh/"+name]=load(dir+"/"+std::to_string(slot)+".bin");}
}

void basicExchange(const std::string& dir){
 const auto bytes=load(dir+"/0.bin");const auto id=idOf(bytes);
 Env e;e.start();
 auto replies=e.push(bytes);
 CHECK(stored(replies,e.relaySide));CHECK(e.received.size()==1&&e.received[0].id==id&&e.received[0].kind==1);
 CHECK(e.relay.storedCount()==1&&e.relay.candidateCount()==1&&LittleFS.files.count("/mesh/00")==1);
 CHECK(!LittleFS.files.count("/mesh/.incoming"));
 // Already held: no WANT; an unrequested BEGIN is refused by policy, never stored.
 CHECK(e.inventory({id}).empty());
 e.rx(msg::begin(99,7,idBytes(id),bytes.size(),bytes.data(),bytes.size()));
 auto refused=e.flush();CHECK(stored(refused,e.relaySide,msg::REJECTED)&&find(refused,msg::RESULT)->reason==msg::POLICY);
 // Forwarding: peer WANT -> BEGIN (hop+1, age += residence) -> RESULT.
 fakeNow+=1250;
 uint64_t request=5;e.rx(msg::ids(msg::WANT,&request,{idBytes(id)}));
 auto sent=e.flush();const msg::Message* begin=find(sent,msg::BEGIN);
 CHECK(begin&&begin->second==5&&hexId(begin->target)==id&&begin->hasBytes&&begin->bytes.n==begin->length);
 std::vector<uint8_t> forwarded(begin->bytes.p,begin->bytes.p+begin->bytes.n);
 auto info=infoOf(forwarded);CHECK(info.id==id&&info.hop==1&&info.age==1250);
 e.rx(msg::result(msg::TARGET_TRANSFER,e.relaySide,begin->first,nullptr,msg::STORED,0));e.flush();
 CHECK(e.notes.back()=="peer accepted "+id.substr(0,8));
 // Unknown event and object requests are answered "unavailable".
 Id missing{};missing[0]=1;request=6;e.rx(msg::ids(msg::WANT,&request,{missing}));
 auto none=e.flush();CHECK(none.size()==1&&none[0].type==msg::RESULT&&none[0].targetKind==msg::TARGET_EVENT&&none[0].code==msg::UNAVAILABLE&&none[0].reason==msg::MISSING);
 request=7;e.rx(msg::ids(msg::FETCH,&request,{missing}));
 none=e.flush();CHECK(none.size()==1&&none[0].targetKind==msg::TARGET_OBJECT&&none[0].code==msg::UNAVAILABLE);
 // Unknown types are ignored; malformed known ones close the link.
 std::vector<uint8_t> unknown={0x82,0x18,0x63,0x00};e.rx(unknown);CHECK(!e.relay.disconnectRequested());
 std::vector<uint8_t> badWant={0x82,0x02,0x00};e.rx(badWant);CHECK(e.relay.disconnectRequested());
 // Reboot: retained but cold, never offered or forwarded in this boot.
 e.relay.disconnected();
 Env reboot(false);CHECK(reboot.relay.storedCount()==1&&reboot.relay.candidateCount()==0);reboot.start();
 request=1;reboot.rx(msg::ids(msg::WANT,&request,{idBytes(id)}));
 none=reboot.flush();CHECK(none.size()==1&&none[0].code==msg::UNAVAILABLE);
 // HELLO rules: wrong version, data before HELLO, equal nonces.
 {Env v(false);v.relay.connected();v.flush();auto h=msg::hello(missing.data(),100,1,0,1024);h[2]=2;v.rx(h);CHECK(v.relay.disconnectRequested());}
 {Env v(false);v.relay.connected();v.flush();v.rx(msg::credit(0,1));CHECK(v.relay.disconnectRequested());}
 {Env v(false);nextRandom=0x11;v.relay.connected();v.flush();uint8_t same[8];for(auto& b:same)b=0x11;v.rx(msg::hello(same,100,1,0,1024));CHECK(v.relay.disconnectRequested());nextRandom=0x80;}
 // A session idle for 45 s releases the single connection.
 {Env v(false);v.start();fakeNow+=46000;v.flush();CHECK(v.relay.disconnectRequested());}
}

void largeAndRejected(const std::string& dir){
 // Multi-fragment receive: initial credit 4096 then one whole-Bundle CREDIT.
 const auto big=load(dir+"/big.bin");const auto id=idOf(big);CHECK(big.size()>9000);
 Env e;e.start();auto want=e.inventory({id});CHECK(want.size()==1);
 e.rx(msg::begin(0,want[0].first,idBytes(id),big.size(),big.data(),4000));
 auto credit=e.flush();CHECK(credit.size()==1&&credit[0].type==msg::CREDIT&&credit[0].length==big.size());
 for(size_t at=4000;at<big.size();at+=4000){e.rx(msg::data(0,big.data()+at,std::min<size_t>(4000,big.size()-at)));}
 auto done=e.flush();CHECK(stored(done,e.relaySide)&&e.relay.storedCount()==1);
 // Sending a large Bundle respects the peer's initial credit and later CREDIT.
 Env& s=e;s.relay.disconnected();s.start(true,1024,4096);
 uint64_t request=0;s.rx(msg::ids(msg::WANT,&request,{idBytes(id)}));
 auto first=s.flush();const msg::Message* begin=find(first,msg::BEGIN);CHECK(begin&&begin->bytes.n==1024&&!find(first,msg::DATA));
 const uint64_t transfer=begin->first,total=begin->length;
 s.rx(msg::credit(transfer,total));auto rest=s.flush();
 size_t got=1024;for(auto& m:rest)if(m.type==msg::DATA){CHECK(m.first==transfer&&m.bytes.n<=4096-24);got+=m.bytes.n;}
 CHECK(got==total);
 // Exceeding the granted window is invalid: rejected, request closed, link kept.
 Env x;x.start();want=x.inventory({id});
 x.rx(msg::begin(0,want[0].first,idBytes(id),big.size(),big.data(),4000));x.flush();
 std::vector<uint8_t> tooMuch(big.size());x.rx(msg::data(0,tooMuch.data(),tooMuch.size()));
 auto rej=x.flush();CHECK(stored(rej,x.relaySide,msg::REJECTED)&&!x.relay.disconnectRequested()&&x.relay.storedCount()==0);
 // A Bundle failing signature checks is refused once and never re-wanted this session.
 const auto tampered=load(dir+"/tampered.bin");const auto tamperedId=load(dir+"/tampered.id");
 const std::string tid(tamperedId.begin(),tamperedId.end());
 Env t;t.start();auto w=t.inventory({tid});CHECK(w.size()==1);
 auto r=t.deliver(w[0],tampered,tid);CHECK(stored(r,t.relaySide,msg::REJECTED)&&find(r,msg::RESULT)->reason==msg::INVALID);
 CHECK(!t.relay.disconnectRequested()&&t.received.empty()&&t.inventory({tid}).empty());
 // A BEGIN announcing a different event ID than the Bundle carries is refused.
 const auto other=load(dir+"/1.bin");Env d;d.start();w=d.inventory({tid});
 r=d.deliver(w[0],other,tid);CHECK(stored(r,d.relaySide,msg::REJECTED)&&d.relay.storedCount()==0);
}

void reconciliation(const std::string& dir){
 std::vector<std::vector<uint8_t>> bundles;std::vector<std::string> ids;
 for(int i=0;i<4;++i){bundles.push_back(load(dir+"/"+std::to_string(i)+".bin"));ids.push_back(idOf(bundles.back()));}
 Env e;e.start();e.push(bundles[0]);e.push(bundles[1]);e.relay.disconnected();
 auto items=[&](std::vector<int> which){std::vector<mesh::Negentropy::Item> v;for(int i:which)v.push_back({infoOf(bundles[i]).createdAt,idBytes(ids[i])});return v;};
 // Relay is side 0 and initiates; peer holds {1,2}: relay wants 2 and offers 0.
 {
  e.start(false);auto ms=e.flush();
  // Side 0 queues the initial RECONCILE on HELLO.
  const msg::Message* first=find(ms,msg::RECONCILE);CHECK(first&&ms.size()==1);
  const msg::Message init=*first;std::string err;
  std::vector<uint8_t> initBytes(init.bytes.p,init.bytes.p+init.bytes.n);
  for(int round=0;round<8;++round){
   mesh::Negentropy peer(items({1,2}),4096-24);std::vector<uint8_t> reply;bool done;
   CHECK(peer.reconcile(initBytes.data(),initBytes.size(),reply,done,err));
   e.rx(msg::reconcile(init.first,reply));auto next=e.flush();
   const msg::Message* again=find(next,msg::RECONCILE);
   if(again){initBytes.assign(again->bytes.p,again->bytes.p+again->bytes.n);continue;}
   const msg::Message* want=find(next,msg::WANT);const msg::Message* inv=find(next,msg::INVENTORY);
   CHECK(want&&want->ids.size()==1&&hexId(want->ids[0])==ids[2]);
   CHECK(inv&&inv->ids.size()==1&&hexId(inv->ids[0])==ids[0]);
   break;
  }
  e.relay.disconnected();
 }
 // Relay is side 1 and answers; the peer initiator learns the exact differences.
 {
  e.start(true);
  mesh::Negentropy peer(items({1,3}),4096-24);auto query=peer.initiate();
  for(int round=0;round<8;++round){
   e.rx(msg::reconcile(0,query));auto ms=e.flush();const msg::Message* r=find(ms,msg::RECONCILE);CHECK(r&&r->first==0);
   std::vector<uint8_t> next;bool done;std::string err;
   CHECK(peer.reconcile(r->bytes.p,r->bytes.n,next,done,err));
   if(done)break;
   query=next;
  }
  CHECK(peer.have().size()==1&&hexId(peer.have()[0])==ids[3]);
  CHECK(peer.need().size()==1&&hexId(peer.need()[0])==ids[0]);
 }
 // Self-consistency across sizes that force fingerprint splitting and frame
 // truncation, down to the 1000-byte minimum frame (1024-byte message limit).
 for(size_t frame:{1000u,4072u}){
  for(int n:{0,1,16,31,32,100,600}){
   std::vector<mesh::Negentropy::Item> a,b;std::vector<Id> onlyA,onlyB;
   for(int i=0;i<n;++i){Id id{};uint32_t x=2166136261u^uint32_t(i);for(auto& byte:id){x^=x<<13;x^=x>>17;x^=x<<5;byte=uint8_t(x);} // Hash-like: sequential IDs collide under the additive fingerprint.
    
    mesh::Negentropy::Item item{uint64_t(1700000000+i/3),id};
    if(i%5==0){a.push_back(item);onlyA.push_back(id);}else if(i%7==0){b.push_back(item);onlyB.push_back(id);}else{a.push_back(item);b.push_back(item);}}
   mesh::Negentropy init(a,frame);auto q=init.initiate();CHECK(q.size()<=frame);bool done=false;int rounds=0;std::string err;
   while(!done){mesh::Negentropy resp(b,frame);std::vector<uint8_t> r;bool d;CHECK(resp.reconcile(q.data(),q.size(),r,d,err));CHECK(r.size()<=frame);
    CHECK(init.reconcile(r.data(),r.size(),q,done,err));CHECK(++rounds<64);CHECK(q.size()<=frame);}
   auto sorted=[](std::vector<Id> v){std::sort(v.begin(),v.end());return v;};
   CHECK(sorted(init.have())==sorted(onlyA)||onlyA.size()>mesh::Negentropy::MAX_RESULT_IDS);
   CHECK(sorted(init.need())==sorted(onlyB));
  }
 }
 // Malformed Negentropy payloads close the link instead of crashing.
 Env m;m.start(true);m.rx(msg::reconcile(0,{0x61,0x05}));CHECK(m.relay.disconnectRequested());
}

void storageSafety(const std::string& dir){
 const auto bytes=load(dir+"/0.bin");const auto id=idOf(bytes);
 // Every failed save stage must be the FINAL diagnostic and must never send "stored".
 const char* stages[]={"write","rename","readback","flush","open-temp","open-final",
                       "close-temp","close-final","rename","readback-compare","readback-size","size-after-flush"};
 for(int fault=0;fault<12;++fault){
  Env e;e.start();auto want=e.inventory({id});CHECK(want.size()==1);
  LittleFS.failWrite=fault==0;LittleFS.failRename=fault==1||fault==8;
  LittleFS.failRead=fault==2;LittleFS.failFlush=fault==3||fault==11;
  LittleFS.failOpenTemp=fault==4;LittleFS.failOpenFinal=fault==5;
  LittleFS.failCloseTemp=fault==6;LittleFS.failCloseFinal=fault==7;
  LittleFS.silentErrors=fault==8||fault==11;
  LittleFS.corruptReadback=fault==9;LittleFS.truncateOnRename=fault==10;
  LittleFS.cleanupErrno=ENAMETOOLONG;
  auto r=e.deliver(want[0],bytes,id);
  CHECK(e.relay.disconnectRequested()&&e.received.empty()&&!stored(r,e.relaySide));
  CHECK(e.notes.back().find(std::string("store ")+stages[fault]+" errno=")==0);
  CHECK(e.notes.back().find("id="+id.substr(0,8))!=std::string::npos);
 }
 // Eviction journals the victim first; evicted IDs are not wanted again, even after reboot.
 {
  Env e;e.start();std::vector<std::string> ids;
  for(int i=0;i<17;++i){auto b=load(dir+"/"+std::to_string(i)+".bin");ids.push_back(idOf(b));CHECK(stored(e.push(b),e.relaySide));}
  CHECK(e.relay.storedCount()==16&&LittleFS.files.at("/mesh-seen").size()==8+36);
  CHECK(e.inventory({ids[0]}).empty());
  e.relay.disconnected();Env reboot(false);reboot.start();CHECK(reboot.inventory({ids[0]}).empty());
  auto fresh=load(dir+"/17.bin");auto w=reboot.inventory({ids[0],idOf(fresh)});CHECK(w.size()==1&&w[0].ids.size()==1&&hexId(w[0].ids[0])==idOf(fresh));
 }
 // Each journal fault preserves the victim and suppresses "stored" and the callback.
 const auto next=load(dir+"/16.bin");const auto nextId=idOf(next);
 for(int fault=0;fault<7;++fault){
  Env reset;seedFullCache(dir);Env e(false);e.start();auto want=e.inventory({nextId});CHECK(want.size()==1);
  const auto before=cacheSnapshot();
  LittleFS.failSeenOpen=fault==0;LittleFS.failSeenWrite=fault==1;LittleFS.failSeenFlush=fault==2;
  LittleFS.failSeenClose=fault==3;LittleFS.failSeenRead=fault==4;LittleFS.failSeenSeek=fault==5;
  LittleFS.corruptSeenRead=fault==6;
  auto r=e.deliver(want[0],next,nextId);
  CHECK(e.relay.disconnectRequested()&&!stored(r,e.relaySide)&&e.received.empty()&&cacheSnapshot()==before);
  CHECK(!LittleFS.files.count("/mesh/.incoming"));
 }
 // Corrupt history fails closed at boot without formatting or forgetting.
 for(int fault=0;fault<3;++fault){
  LittleFS=MockFS();LittleFS.directory=true;auto bad=load(dir+"/seen-id0.bin");
  if(fault==0)bad.pop_back();
  if(fault==1)bad[0]^=1;
  if(fault==2)bad[8]^=1;
  LittleFS.files["/mesh-seen"]=bad;mesh::Relay relay;
  CHECK(!relay.begin([](const uint8_t*,size_t){return true;},[](const std::string&){}));
  CHECK(!LittleFS.format&&LittleFS.files.at("/mesh-seen")==bad);
 }
 // Equal 64-bit prefixes are not sufficient for dedup: all 256 bits are compared.
 {Env reset;LittleFS.files["/mesh-seen"]=load(dir+"/seen-collision.bin");Env e(false);e.start();CHECK(e.inventory({id}).size()==1);}
 {Env reset;LittleFS.files["/mesh-seen"]=load(dir+"/seen-id0.bin");Env e(false);e.start();CHECK(e.inventory({id}).empty());}
}

void gatewaySafety(const std::string& dir){
 Env reset;seedFullCache(dir);Env e(false);e.relay.enableGateway();e.start();
 auto pending=e.relay.gatewayPending();CHECK(pending.size()==16);
 const auto next=load(dir+"/16.bin");const auto nextId=idOf(next);
 // All slots pinned: no acquisition, nothing evicted.
 CHECK(e.inventory({nextId}).empty()&&LittleFS.evictions==0);
 std::vector<uint8_t> raw;CHECK(e.relay.gatewayRead(pending[0],raw)&&idOf(raw)==pending[0]);
 e.relay.gatewayDelivered(pending[0]);CHECK(e.relay.gatewayPending().size()==15);
 auto w=e.inventory({nextId});CHECK(w.size()==1);CHECK(stored(e.deliver(w[0],next,nextId),e.relaySide));
 CHECK(LittleFS.evictions==1&&e.relay.gatewayPending().size()==16);
 // Control events: PLC genesis and revocation are exportable; other topics are not pinned.
 Env c;c.relay.enableGateway();c.start();
 auto plc=load(dir+"/plc.bin"),rev=load(dir+"/revocation.bin"),other=load(dir+"/other-control.bin");
 CHECK(stored(c.push(plc),c.relaySide)&&stored(c.push(rev),c.relaySide)&&stored(c.push(other),c.relaySide));
 auto ids=c.relay.gatewayPending();CHECK(ids.size()==2);
 CHECK(std::find(ids.begin(),ids.end(),idOf(plc))!=ids.end()&&std::find(ids.begin(),ids.end(),idOf(rev))!=ids.end());
 CHECK(infoOf(plc).topic=="mosimo.jisaku.dev/plc");
}

void advertisement(const std::string& dir){
 Env e;uint8_t a[4],b[4];e.relay.advertisement(a);
 // Empty candidate set: SHA-256 of the empty string, first 3 bytes; flags 0.
 CHECK(a[0]==0xe3&&a[1]==0xb0&&a[2]==0xc4&&a[3]==0);
 e.start();e.push(load(dir+"/0.bin"));e.relay.advertisement(b);CHECK(std::memcmp(a,b,3)!=0);
}

int main(int argc,char**argv){
 CHECK(argc==2);std::string dir=argv[1];
 basicExchange(dir);largeAndRejected(dir);reconciliation(dir);storageSafety(dir);gatewaySafety(dir);advertisement(dir);
 std::cout<<"PASS relay exchange/storage: "<<checks<<" assertions\n";
 return 0;
}
