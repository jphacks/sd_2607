// Run via test_relay.py: real MeshBundle/mbedTLS/ArduinoJson, fake FS/clock.
// No BLE or filesystem writes occur in the capture-only send callback.
#include "MeshRelay.h"
#include <ArduinoJson.h>
#include <LittleFS.h>

#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
MockFS LittleFS;
uint64_t fakeNow=100000;
static size_t checks=0;
#define CHECK(x) do{++checks;if(!(x)){std::cerr<<"FAIL "<<__LINE__<<": "<<#x<<"\n";std::abort();}}while(0)
std::vector<uint8_t> load(const std::string&p){std::ifstream f(p,std::ios::binary);CHECK(bool(f));return {(std::istreambuf_iterator<char>(f)),{}};}
std::string idOf(const std::vector<uint8_t>&b){mesh::BundleInfo i;std::string err;CHECK(mesh::validateBundle(b.data(),b.size(),i,err));return i.id;}
std::string control(const std::string&type,const std::vector<std::string>&ids={}){
 std::string s="{\"v\":1,\"type\":\""+type+"\",\"ids\":[";
 for(size_t i=0;i<ids.size();++i){if(i)s+=',';s+='"';s+=ids[i];s+='"';}return s+"]}";
}
const std::string hello="{\"v\":1,\"type\":\"hello\",\"node\":\"fedcba9876543210\",\"max\":24000}";
struct Env {
 mesh::Relay relay;bool allow=true;unsigned calls=0;
 std::vector<std::vector<uint8_t>> out;std::vector<std::string>notes;
 Env(bool reset=true){if(reset){LittleFS=MockFS();fakeNow=100000;}CHECK(relay.begin("0123456789abcdef",[&](const uint8_t*p,size_t n){++calls;if(!allow)return false;out.emplace_back(p,p+n);return true;},[&](const std::string&s){notes.push_back(s);}));CHECK(!LittleFS.format);}
 void rx(const std::string&s){relay.receive(reinterpret_cast<const uint8_t*>(s.data()),s.size());}
 void rx(const std::vector<uint8_t>&s){relay.receive(s.data(),s.size());}
 void start(){relay.connected();relay.tick();CHECK(type()=="hello");rx(hello);relay.tick();CHECK(type()=="inventory");}
 std::string type(){if(out.empty())return "";if(out.back()[0]==0x9f)return "bundle";JsonDocument d;CHECK(!deserializeJson(d,out.back().data(),out.back().size()));return d["type"].as<std::string>();}
 size_t ids(){JsonDocument d;CHECK(!deserializeJson(d,out.back().data(),out.back().size()));return d["ids"].size();}
 void request(const std::string&id){rx(control("inventory",{id}));relay.tick();CHECK(type()=="want");CHECK(ids()==1);}
};
// Slots are storage locations only; recover full identity from verified data.
void checkStoredPaths(){
 std::vector<std::string> ids;
 for(const auto& file:LittleFS.files){
  CHECK(file.first.size()==8&&file.first.compare(0,6,"/mesh/")==0);
  std::string name=file.first.substr(6);
  CHECK(name[0]>='0'&&name[0]<='1'&&name[1]>='0'&&name[1]<='9');
  CHECK(std::stoul(name)<16);
  std::string id=idOf(file.second);CHECK(id.size()==64);
  CHECK(std::find(ids.begin(),ids.end(),id)==ids.end());ids.push_back(id);
 }
}
void seedFullCache(const std::string& dir){
 LittleFS.files.clear();
 for(int slot=0;slot<16;++slot){std::string name=std::to_string(slot);if(slot<10)name="0"+name;LittleFS.files["/mesh/"+name]=load(dir+"/"+std::to_string(slot)+".bin");}
}
int main(int argc,char**argv){
 CHECK(argc==2);std::string dir=argv[1];auto bytes=load(dir+"/0.bin");std::string id=idOf(bytes);

 // Provisioning superblock requires components <=32 and whole paths <=63.
 // Check both sides of rename, without modifying the source on failure.
 {
  LittleFS=MockFS();CHECK(LittleFS.mkdir("/mesh"));
  const std::string valid="/mesh/"+std::string(32,'0'), tooLong="/mesh/"+std::string(33,'1');
  File f=LittleFS.open(valid.c_str(),"w");CHECK(bool(f));f.close();
  errno=0;CHECK(!LittleFS.rename(valid.c_str(),(valid+".bp").c_str()));CHECK(errno==ENAMETOOLONG);
  CHECK(LittleFS.files.count(valid)==1&&LittleFS.files.size()==1);
  errno=0;CHECK(!LittleFS.rename(tooLong.c_str(),valid.c_str()));CHECK(errno==ENAMETOOLONG);
  errno=0;CHECK(!LittleFS.open(tooLong.c_str(),"w"));CHECK(errno==ENAMETOOLONG);
  errno=0;CHECK(!LittleFS.exists(tooLong.c_str()));CHECK(errno==ENAMETOOLONG);
  errno=0;CHECK(!LittleFS.mkdir(tooLong.c_str()));CHECK(errno==ENAMETOOLONG);
  errno=0;CHECK(!LittleFS.remove(tooLong.c_str()));CHECK(errno==ENAMETOOLONG);
  CHECK(LittleFS.validPath(("/"+std::string(30,'a')+"/"+std::string(31,'b')).c_str()));
  const std::string shorter="/mesh/"+std::string(31,'2');
  CHECK(LittleFS.rename(valid.c_str(),shorter.c_str()));CHECK(LittleFS.files.count(shorter)==1);
 }
 {
  Env e;e.start();e.request(id);e.rx(bytes);if(LittleFS.renameNameErrors)std::cerr<<"mock rename: exceeds provisioned name_max=32, errno="<<ENAMETOOLONG<<"\n";CHECK(e.relay.storedCount()==1);CHECK(LittleFS.files.size()==1);CHECK(LittleFS.files.count("/mesh/00")==1);checkStoredPaths();CHECK(!LittleFS.files.count("/mesh/.incoming"));CHECK(LittleFS.flushes==1&&LittleFS.renames==1);CHECK(e.type()=="want");
  e.allow=false;e.relay.tick();CHECK(e.type()=="want");CHECK(!e.relay.disconnectRequested());fakeNow+=250;e.allow=true;e.relay.tick();CHECK(e.type()=="ack");e.relay.tick();CHECK(e.type()=="inventory"&&e.ids()==1);
  auto saved=LittleFS.files.begin()->second;fakeNow+=1000;e.rx(bytes);e.relay.tick();CHECK(e.type()=="ack");CHECK(LittleFS.files.begin()->second==saved);CHECK(LittleFS.renames==1);
  e.relay.disconnected();e.start();CHECK(e.ids()==1);e.rx(control("want",{id}));e.relay.tick();CHECK(e.type()=="bundle");mesh::BundleInfo sent;std::string err;CHECK(mesh::validateBundle(e.out.back().data(),e.out.back().size(),sent,err));CHECK(sent.id==id&&sent.hop==1&&sent.age==1250);
  e.rx(control("ack",{id}));size_t count=e.out.size();fakeNow+=10000;e.relay.tick();CHECK(e.type()=="inventory");e.relay.tick();CHECK(e.out.size()==count+1);
  Env reboot(false);CHECK(reboot.relay.storedCount()==1);reboot.start();CHECK(reboot.ids()==0);count=reboot.out.size();reboot.rx(control("inventory",{id}));reboot.relay.tick();CHECK(reboot.out.size()==count);reboot.rx(control("want",{id}));reboot.relay.tick();CHECK(reboot.out.size()==count);reboot.rx(bytes);CHECK(reboot.relay.disconnectRequested());
 }
 {Env e;e.start();e.rx(bytes);CHECK(e.relay.disconnectRequested());CHECK(e.relay.storedCount()==0);}
 // Every failed stage must be the FINAL diagnostic, preserve the original
 // errno across cleanup (or say it was not reported), and never send an ACK.
 const char* stages[]={"write","rename","readback","flush","open-temp","open-final",
                       "close-temp","close-final","rename","readback-compare","readback-size","size-after-flush"};
 const int codes[]={ENOSPC,EIO,EIO,EIO,EACCES,EACCES,EIO,EIO,0,0,0,0};
 for(int fault=0;fault<12;++fault){
  Env e;e.start();e.request(id);
  LittleFS.failWrite=fault==0;LittleFS.failRename=fault==1||fault==8;
  LittleFS.failRead=fault==2;LittleFS.failFlush=fault==3||fault==11;
  LittleFS.failOpenTemp=fault==4;LittleFS.failOpenFinal=fault==5;
  LittleFS.failCloseTemp=fault==6;LittleFS.failCloseFinal=fault==7;
  LittleFS.silentErrors=fault==8||fault==11;
  LittleFS.corruptReadback=fault==9;LittleFS.truncateOnRename=fault==10;
  LittleFS.cleanupErrno=ENAMETOOLONG;
  e.rx(bytes);CHECK(e.relay.disconnectRequested());CHECK(e.relay.storedCount()==0);
  const std::string diagnostic=e.notes.back();
  CHECK(diagnostic.find(std::string("store ")+stages[fault]+" errno="+std::to_string(codes[fault]))==0);
  CHECK(diagnostic.find("id="+id.substr(0,8))!=std::string::npos);
  CHECK(diagnostic.find(" "+std::to_string(bytes.size())+"B; no ACK")!=std::string::npos);
  CHECK(diagnostic.size()<180);CHECK(LittleFS.renameNameErrors==0);
  if(!codes[fault])CHECK(diagnostic.find("(not reported)")!=std::string::npos);
  e.relay.tick();CHECK(e.type()=="want");
 }
 {Env e;e.start();e.request(id);e.rx(bytes);e.relay.tick();fakeNow+=1000;e.rx(bytes);e.relay.tick();fakeNow=100000+86400000;e.relay.tick();CHECK(e.type()=="inventory"&&e.ids()==0);CHECK(e.relay.storedCount()==1);}
 {Env e;e.start();e.request(id);e.rx(bytes);e.relay.tick();LittleFS.files.begin()->second[10]^=1;e.rx(bytes);CHECK(e.relay.disconnectRequested());}
 {Env e;e.relay.connected();e.allow=false;e.relay.tick();unsigned calls=e.calls;fakeNow+=249;e.relay.tick();CHECK(e.calls==calls);fakeNow+=1;e.allow=true;e.relay.tick();CHECK(e.type()=="hello");CHECK(!e.relay.disconnectRequested());}
 {Env e;e.relay.connected();e.allow=false;e.relay.tick();fakeNow+=10000;e.relay.tick();CHECK(e.relay.disconnectRequested());}
 {Env e;e.relay.connected();fakeNow+=60000;e.relay.tick();CHECK(e.relay.disconnectRequested());}
 {Env e;e.start();e.request(id);fakeNow+=30000;e.relay.tick();CHECK(e.relay.disconnectRequested());}
 {Env e;e.start();e.request(id);e.rx(bytes);e.relay.tick();e.relay.tick();e.rx(control("want",{id}));e.relay.tick();CHECK(e.type()=="bundle");for(int i=0;i<2;++i){fakeNow+=10000;e.relay.tick();e.relay.tick();CHECK(e.type()=="bundle");}fakeNow+=10000;e.relay.tick();e.relay.tick();CHECK(e.relay.disconnectRequested());}

 {
  Env e;e.start();std::vector<std::string>ids;std::vector<std::vector<uint8_t>>all;
  for(int i=0;i<16;++i){all.push_back(load(dir+"/"+std::to_string(i)+".bin"));ids.push_back(idOf(all.back()));}
  e.rx(control("inventory",ids));e.relay.tick();CHECK(e.type()=="want"&&e.ids()==16);
  for(auto&b:all){e.rx(b);}CHECK(!e.relay.disconnectRequested());CHECK(e.relay.storedCount()==16);CHECK(LittleFS.files.size()==16);checkStoredPaths();
  for(int i=0;i<16;++i){e.relay.tick();CHECK(e.type()=="ack");}e.relay.tick();CHECK(e.type()=="inventory"&&e.ids()==16);
  size_t count=e.out.size();e.rx(control("inventory",{idOf(load(dir+"/16.bin"))}));e.relay.tick();CHECK(e.out.size()==count);
  Env reboot(false);CHECK(reboot.relay.storedCount()==16);reboot.start();CHECK(reboot.ids()==0);
 }
 // Inventory alone cannot evict. A requested but invalid signed bundle cannot
 // evict either, even when all sixteen restored entries are cold.
 {
  Env reset;seedFullCache(dir);Env e(false);e.start();CHECK(e.ids()==0);
  const auto before=LittleFS.files;auto next=load(dir+"/16.bin");e.request(idOf(next));
  CHECK(LittleFS.files==before);e.rx(load(dir+"/tampered16.bin"));
  CHECK(e.relay.disconnectRequested());CHECK(LittleFS.files==before);CHECK(LittleFS.evictions==0);
 }
 // A DIFFERENT verified event replaces a cold entry only after staging sync.
 {
  Env reset;seedFullCache(dir);Env e(false);e.start();auto next=load(dir+"/16.bin");
  LittleFS.requireStagedEviction=true;e.request(idOf(next));e.rx(next);
  CHECK(!e.relay.disconnectRequested());CHECK(e.relay.storedCount()==16);CHECK(LittleFS.files.size()==16);
  CHECK(LittleFS.evictions==1);CHECK(LittleFS.files.at("/mesh/00")==next);
  CHECK(LittleFS.peakFiles==17&&LittleFS.peakBytes<=384000+24000);
  e.relay.tick();CHECK(e.type()=="ack");e.relay.tick();CHECK(e.ids()==1);
 }
 // A mixed full cache can evict a verified entry, never its invalid slot.
 {
  Env reset;seedFullCache(dir);const std::vector<uint8_t> invalid={0x9f};LittleFS.files["/mesh/00"]=invalid;
  Env e(false);e.start();auto next=load(dir+"/16.bin");e.request(idOf(next));e.rx(next);
  CHECK(!e.relay.disconnectRequested());CHECK(e.relay.storedCount()==15);CHECK(LittleFS.files.size()==16);
  CHECK(LittleFS.files.at("/mesh/00")==invalid);CHECK(LittleFS.files.at("/mesh/01")==next);
 }
 // Oldest live is replaced even if its duplicate was just received. An
 // expired entry takes priority over that older but still-live entry.
 for(bool expired:{false,true}){
  Env e;e.start();std::vector<std::string> ids;std::vector<std::vector<uint8_t>> packets;
  for(int slot=0;slot<16;++slot){packets.push_back(load(dir+"/"+(expired&&slot==5?std::string("aged5"):std::to_string(slot))+".bin"));ids.push_back(idOf(packets.back()));}
  e.rx(control("inventory",ids));e.relay.tick();CHECK(e.type()=="want"&&e.ids()==16);
  for(const auto& packet:packets){++fakeNow;e.rx(packet);}CHECK(e.relay.storedCount()==16);
  fakeNow+=100;e.rx(packets[0]);CHECK(LittleFS.renames==16);
  e.relay.disconnected();e.start();auto next=load(dir+"/16.bin");e.request(idOf(next));
  LittleFS.requireStagedEviction=true;e.rx(next);CHECK(!e.relay.disconnectRequested());
  CHECK(LittleFS.files.at(expired?"/mesh/05":"/mesh/00")==next);
  CHECK(e.relay.storedCount()==16&&LittleFS.evictions==1);
 }
 // Failed writes/flush/remove preserve the old cache. Failed rename leaves
 // fifteen slots; failed final readback leaves sixteen files but only fifteen
 // indexed entries. None can ACK the replacement.
 for(int fault=0;fault<6;++fault){
  Env reset;seedFullCache(dir);Env e(false);e.start();auto next=load(dir+"/16.bin");e.request(idOf(next));
  const auto before=LittleFS.files;LittleFS.requireStagedEviction=true;
  LittleFS.failWrite=fault==0;LittleFS.failFlush=fault==1;LittleFS.failRemove=fault==2;
  LittleFS.failRename=fault==3;LittleFS.failRead=fault==4;LittleFS.truncateOnRename=fault==5;
  e.rx(next);CHECK(e.relay.disconnectRequested());e.relay.tick();CHECK(e.type()=="want");
  CHECK(!LittleFS.files.count("/mesh/.incoming"));
  if(fault<3){CHECK(LittleFS.files==before);CHECK(e.relay.storedCount()==16);CHECK(LittleFS.evictions==0);}
  else {CHECK(e.relay.storedCount()==15);CHECK(LittleFS.evictions==1);CHECK(LittleFS.files.size()==(fault==3?15:16));}
  if(fault==3){
   LittleFS.failRename=false;e.relay.disconnected();e.start();e.request(idOf(next));e.rx(next);
   CHECK(!e.relay.disconnectRequested());CHECK(e.relay.storedCount()==16);CHECK(LittleFS.files.size()==16);
  }
 }
 // Repeated inventory can expose IDs displaced earlier this same session.
 // completed_ must retain that history and stop at sixteen distinct writes.
 {
  Env reset;seedFullCache(dir);Env e(false);e.start();std::vector<std::string> ids;
  std::map<std::string,std::vector<uint8_t>> packets;
  for(int i=0;i<17;++i){auto packet=load(dir+"/"+std::to_string(i)+".bin");std::string key=idOf(packet);ids.push_back(key);packets[key]=packet;}
  e.rx(control("inventory",ids));e.relay.tick();
  for(int accepted=0;accepted<16;++accepted){
   CHECK(e.type()=="want");JsonDocument want;CHECK(!deserializeJson(want,e.out.back().data(),e.out.back().size()));
   CHECK(want["ids"].size()==1);e.rx(packets.at(want["ids"][0].as<std::string>()));CHECK(!e.relay.disconnectRequested());
   e.relay.tick();CHECK(e.type()=="ack");e.relay.tick();CHECK(e.type()=="inventory");e.relay.tick();
  }
  CHECK(LittleFS.evictions==16&&e.relay.storedCount()==16);
  size_t sent=e.out.size();e.rx(control("inventory",ids));e.relay.tick();CHECK(e.out.size()==sent);
  e.relay.disconnected();e.start();e.rx(control("inventory",ids));e.relay.tick();CHECK(e.type()=="want");
 }
 std::vector<std::string>bad={"{}","{\"v\":1,\"v\":1,\"type\":\"hello\",\"node\":\"fedcba9876543210\",\"max\":24000}","{\"v\":1,\"\u0076\":1,\"type\":\"hello\",\"node\":\"fedcba9876543210\",\"max\":24000}",hello+"{}",hello+"garbage",hello.substr(0,hello.size()-1)+",}","{'v':1}","{\"v\":1.0,\"type\":\"hello\",\"node\":\"fedcba9876543210\",\"max\":24000}","{\"v\":1,\"type\":\"hello\",\"node\":\"fedcba9876543210\",\"max\":24000.0}"};
 bad.push_back(R"json({"v":1,"\u0076":1,"type":"hello","node":"fedcba9876543210","max":24000})json");
 bad.push_back(R"json({"v\u0000":1,"type":"hello","node":"fedcba9876543210","max":24000})json");
 bad.push_back(R"json({"v":1,"type\u0000":"hello","node":"fedcba9876543210","max":24000})json");
 for(auto&b:bad){Env e;e.relay.connected();e.rx(b);CHECK(e.relay.disconnectRequested());}
 {Env e;e.relay.connected();e.rx(hello.substr(0,hello.size()-1)+",\"future\":{\"route\":[true,null,1.5]}} \n");CHECK(!e.relay.disconnectRequested());e.rx(hello);CHECK(e.relay.disconnectRequested());}
 for(auto&s:std::vector<std::string>{control("inventory",{id,id}),control("ack",{}),control("want",{std::string(64,'A')}),control("bogus",{})}){Env e;e.start();e.rx(s);CHECK(e.relay.disconnectRequested());}
 {Env e;e.start();e.rx(std::string(10241,'{'));CHECK(e.relay.disconnectRequested());}
 {Env e;e.start();std::vector<uint8_t>b(24001);e.rx(b);CHECK(e.relay.disconnectRequested());}
 {Env e;e.start();for(unsigned i=0;i<512;++i)e.rx(control("inventory"));CHECK(e.relay.disconnectRequested());}
 {Env e;e.start();for(unsigned i=0;i<4096;++i){fakeNow+=1000;e.rx(control("inventory"));}CHECK(e.relay.disconnectRequested());}
 {Env e;e.start();std::vector<std::string>ids;for(int i=0;i<128;++i){std::string s(64,'0');s[62]="0123456789abcdef"[i/16];s[63]="0123456789abcdef"[i%16];ids.push_back(s);}for(unsigned i=0;i<500&&!e.relay.disconnectRequested();++i){fakeNow+=250;e.rx(control("inventory",ids));}CHECK(e.relay.disconnectRequested());}
 {Env e;e.start();std::vector<std::string>ids(129,id);e.rx(control("inventory",ids));CHECK(e.relay.disconnectRequested());}
 {Env e;LittleFS.files["/mesh/.incoming"]=bytes;Env reboot(false);CHECK(!LittleFS.files.count("/mesh/.incoming"));}
 {Env e;LittleFS.files["/mesh/corrupt"]=bytes;Env reboot(false);CHECK(reboot.relay.storedCount()==0);CHECK(LittleFS.files.size()==1);}
 // Restore accepts only bounded slot names, never arbitrary filenames.
 for(const std::string& name:{std::string("0"),std::string("16"),std::string("99"),std::string("AA"),std::string("000")}){
  Env e;LittleFS.files["/mesh/"+name]=bytes;Env reboot(false);
  CHECK(reboot.relay.storedCount()==0);CHECK(LittleFS.files.size()==1);
  reboot.start();CHECK(reboot.ids()==0);
 }
 // Invalid occupied slots are retained and skipped, not overwritten. Reads
 // and forwarding must use Entry.path (01 here), not derive a name from ID.
 {
  Env reset;const std::vector<uint8_t> invalid={0x9f};LittleFS.files["/mesh/00"]=invalid;
  Env e(false);CHECK(e.relay.storedCount()==0);e.start();e.request(id);e.rx(bytes);
  CHECK(!e.relay.disconnectRequested());CHECK(e.relay.storedCount()==1);
  CHECK(LittleFS.files.at("/mesh/00")==invalid);CHECK(LittleFS.files.at("/mesh/01")==bytes);
  e.relay.tick();CHECK(e.type()=="ack");e.relay.disconnected();e.start();
  e.rx(control("want",{id}));e.relay.tick();CHECK(e.type()=="bundle");CHECK(idOf(e.out.back())==id);
 }
 // A bundle in arbitrary slot 13 restores by VERIFIED CONTENTS, and remains
 // cold. The same full ID in two slots is indexed once but both consume space.
 {
  Env reset;LittleFS.files["/mesh/13"]=bytes;LittleFS.files["/mesh/15"]=bytes;
  Env e(false);CHECK(e.relay.storedCount()==1);CHECK(LittleFS.files.size()==2);e.start();CHECK(e.ids()==0);
  size_t count=e.out.size();e.rx(control("inventory",{id}));e.relay.tick();CHECK(e.out.size()==count);
  auto next=load(dir+"/1.bin");std::string nextId=idOf(next);e.request(nextId);e.rx(next);
  CHECK(e.relay.storedCount()==2);CHECK(LittleFS.files.at("/mesh/00")==next);
  CHECK(LittleFS.files.at("/mesh/13")==bytes&&LittleFS.files.at("/mesh/15")==bytes);
 }
 // Sixteen invalid files still exhaust capacity despite storedCount()==0.
 {
  Env reset;
  for(int slot=0;slot<16;++slot){std::string name=std::to_string(slot);if(slot<10)name="0"+name;LittleFS.files["/mesh/"+name]={0x9f};}
  Env e(false);CHECK(e.relay.storedCount()==0);e.start();size_t count=e.out.size();
  e.rx(control("inventory",{id}));e.relay.tick();CHECK(e.out.size()==count);CHECK(LittleFS.files.size()==16);
 }
 // Signature/CRC verification and the requested event ID gate precede writes.
 for(const std::string& name : {std::string("tampered.bin"), std::string("16.bin")}) {
  Env e;e.start();e.request(id);e.rx(load(dir+"/"+name));
  CHECK(e.relay.disconnectRequested());CHECK(e.relay.storedCount()==0);
  CHECK(LittleFS.files.empty());e.relay.tick();CHECK(e.type()=="want");
 }
 // A tiny but valid peer capability must never receive an oversized bundle.
 {
  Env e;e.start();e.request(id);e.rx(bytes);e.relay.tick();e.relay.disconnected();
  e.relay.connected();e.relay.tick();std::string small=hello;
  small.replace(small.find("24000"),5,"1");e.rx(small);e.relay.tick();
  CHECK(e.type()=="inventory"&&e.ids()==1);e.rx(control("want",{id}));
  size_t count=e.out.size();e.relay.tick();CHECK(e.out.size()==count);
  CHECK(!e.relay.disconnectRequested());
 }
 // Mount failure cannot trigger format-on-fail, even on a fresh filesystem.
 {
  LittleFS=MockFS();LittleFS.mounted=false;mesh::Relay relay;
  CHECK(!relay.begin("0123456789abcdef",[](const uint8_t*,size_t){return true;},{}));
  CHECK(!LittleFS.format);relay.connected();CHECK(relay.disconnectRequested());
 }
 std::cout<<"PASS Relay: "<<checks<<" assertions (real codec, ArduinoJson 7.4.2, mocked LittleFS/clock)\n";
}
