#include "SidecarCollectionsV5Transfer.h"
#include <BuildScratch.h>
#include <HalDisplay.h>
#include <InflateStream.h>
#include <Logging.h>
#include <cstring>
#include <cstdio>

namespace sidecar::v5 {
namespace {
uint16_t get16(const uint8_t* p) { return p[0] | (static_cast<uint16_t>(p[1]) << 8); }
uint32_t get32(const uint8_t* p) { return p[0] | (uint32_t(p[1])<<8) | (uint32_t(p[2])<<16) | (uint32_t(p[3])<<24); }
uint64_t get64(const uint8_t* p) { return get32(p) | (uint64_t(get32(p+4))<<32); }
uint32_t rawUpdate(uint32_t c, const uint8_t* p, size_t n) { for(size_t i=0;i<n;++i){c^=p[i];for(uint8_t b=0;b<8;++b)c=(c>>1)^(0xEDB88320u&(0u-(c&1u)));}return c; }
bool packetCrc(const uint8_t* p, size_t n) { return n >= 5 && crc32(p,n-4)==get32(p+n-4); }
uint32_t getBe32(const uint8_t* p) {
  return (uint32_t(p[0])<<24) | (uint32_t(p[1])<<16) | (uint32_t(p[2])<<8) | p[3];
}
struct FileFillContext {
  HalFile* file = nullptr;
  uint32_t remaining = 0;
  uint8_t buffer[1024];
};
size_t fillInflate(void* opaque, const uint8_t** data) {
  auto* context = static_cast<FileFillContext*>(opaque);
  if (!context || !context->file || !context->remaining) return 0;
  const size_t wanted = context->remaining < sizeof(context->buffer)
                            ? context->remaining : sizeof(context->buffer);
  const int count = context->file->read(context->buffer, wanted);
  if (count <= 0) return 0;
  context->remaining -= static_cast<uint32_t>(count);
  *data = context->buffer;
  return static_cast<size_t>(count);
}
bool readExact(HalFile& file, uint8_t* output, size_t length) {
  return file.read(output, length) == static_cast<int>(length);
}

// Sidecar receives and decodes a page while the panel is idle. Lend the display
// buffer to InflateStream for its ~43 KB state/window, then return it before the
// committed page is loaded and painted. This avoids relying on a large contiguous
// heap block, which is normally smaller than the streaming inflater at runtime.
class DecodeScratchLoan {
 public:
  DecodeScratchLoan() {
    uint32_t size = 0;
    uint8_t* memory = display.lendFrameBufferStorage(&size);
    if (memory != nullptr) {
      buildscratch::lend(memory, size);
      active_ = true;
    }
  }
  ~DecodeScratchLoan() {
    if (!active_) return;
    buildscratch::reclaim();
    display.returnFrameBufferStorage();
  }
  bool active() const { return active_; }

 private:
  bool active_ = false;
};
}

Transfer::Transfer(uint16_t w, uint16_t h) : width_(w), height_(h) {}
bool Transfer::handles(uint8_t op) const { return pageOpen_ || op==0x09||op==0x0B||op==0x0C||op==0x0D||op==0x0E; }
bool Transfer::onPacket(const uint8_t* p,uint16_t n){ if(!p||!n){if(pageOpen_)closePage(true);return false;} switch(p[0]){case 0x09:return beginPage(p,n);case 0x0B:return beginSession(p,n);case 0x0C:return commit(p,n);case 0x0D:return abort(p,n);case 0x0E:return setEnabled(p,n);case 0x02:return data(p,n);case 0x03:return end(p,n);default:if(pageOpen_)closePage(true);return false;} }
void Transfer::onDisconnect(){ closePage(true); pagesThisConnection_=0; }
void Transfer::closePage(bool remove){
  if(pageFile_){pageFile_.flush();pageFile_.close();}
  if(remove&&pagePath_[0]&&Storage.exists(pagePath_))Storage.remove(pagePath_);
  if(remove&&encodedPath_[0]&&Storage.exists(encodedPath_))Storage.remove(encodedPath_);
  pageOpen_=false;pagePath_[0]=0;encodedPath_[0]=0;received_=0;encodedLength_=0;
  nextSeq_=0;rawCrc_=0xFFFFFFFFu;encoding_=0;
}

bool Transfer::beginSession(const uint8_t* p,uint16_t n){
  if(pageOpen_||n<34||!packetCrc(p,n))return false; const uint8_t count=p[21];
  if(!count||count>kMaxPagesPerCollection||n!=30+4*count)return false;
  SessionJournal s{}; s.sessionId=get64(p+1);s.collectionId=get32(p+9);s.baseRootGeneration=get32(p+13);
  s.baseCollectionGeneration=get32(p+17);s.resultingPageCount=count;s.mode=static_cast<SessionMode>(p[22]);
  s.requiredPageBitmap=get16(p+23); if(p[25]!=4*count || (s.mode!=SessionMode::Create&&s.mode!=SessionMode::FullReplace))return false;
  for(uint8_t i=0;i<count;++i)s.pageCrc[i]=get32(p+26+4*i);
  RootIndex root{}; const bool haveRoot=store_.loadNewestRoot(root); CollectionManifest old{};
  if (haveRoot) {
    uint16_t total = 0;
    for (uint8_t i=0;i<root.entryCount;++i) {
      CollectionManifest checked{};
      if (!store_.loadCollectionManifest(root.entries[i].collectionId,
                                         root.entries[i].expectedGeneration, checked) ||
          total > kMaxPublishedPages - checked.pageCount) return false;
      total = static_cast<uint16_t>(total + checked.pageCount);
    }
    if (total != root.totalPublishedPages) return false;
  }
  if(s.mode==SessionMode::Create){
    if(s.baseCollectionGeneration||s.baseRootGeneration!=(haveRoot?root.generation:0))return false;
    if(haveRoot){if(root.entryCount>=kMaxCollections||root.totalPublishedPages+count>kMaxPublishedPages)return false;for(uint8_t i=0;i<root.entryCount;++i)if(root.entries[i].collectionId==s.collectionId)return false;}
    s.candidateCollectionGeneration=1;s.candidateBank=0;
  }else{
    if(!haveRoot||root.generation!=s.baseRootGeneration)return false; bool found=false;
    for(uint8_t i=0;i<root.entryCount;++i)if(root.entries[i].collectionId==s.collectionId&&root.entries[i].expectedGeneration==s.baseCollectionGeneration)found=true;
    if(!found||!store_.loadCollectionManifest(s.collectionId,s.baseCollectionGeneration,old))return false;
    if(root.totalPublishedPages-old.pageCount+count>kMaxPublishedPages)return false;
    s.candidateCollectionGeneration=s.baseCollectionGeneration+1;if(!s.candidateCollectionGeneration)return false;s.candidateBank=old.bank^1u;
  }
  s.state=SessionState::Receiving;s.receivedPageBitmap=0;
  CapacityAdmission admission{}; const uint64_t bytes=uint64_t((width_+7u)/8u)*height_*count;
  if(!store_.createSession(s,bytes,admission)){
    SessionJournal existing{}; if(!store_.loadNewestSession(existing)||existing.sessionId!=s.sessionId||
      existing.collectionId!=s.collectionId||existing.baseRootGeneration!=s.baseRootGeneration||
      existing.baseCollectionGeneration!=s.baseCollectionGeneration||
      existing.candidateCollectionGeneration!=s.candidateCollectionGeneration||
      existing.resultingPageCount!=s.resultingPageCount||existing.candidateBank!=s.candidateBank||
      existing.mode!=s.mode||existing.requiredPageBitmap!=s.requiredPageBitmap)return false;
    for(uint8_t i=0;i<count;++i)if(existing.pageCrc[i]!=s.pageCrc[i])return false;
  }
  return true;
}

bool Transfer::beginPage(const uint8_t* p,uint16_t n){
  if(pageOpen_||pagesThisConnection_>=4||n!=32){LOG_ERR("SCV5","begin page state open=%u pages=%u len=%u",pageOpen_?1u:0u,static_cast<unsigned>(pagesThisConnection_),static_cast<unsigned>(n));return false;} SessionJournal s{};if(!store_.loadNewestSession(s)){LOG_ERR("SCV5","begin page has no durable session");return false;}
  const uint16_t x=get16(p+1),y=get16(p+3),w=get16(p+5),h=get16(p+7);const uint8_t encoding=p[9];const uint32_t length=get32(p+10);
  const uint64_t sid=get64(p+14);const uint8_t page=p[22];const uint32_t expected=get32(p+23),generation=get32(p+27);
  const uint32_t rawLength=uint32_t((width_+7u)/8u)*height_;
  const bool validLength=encoding==0 ? length==rawLength : (encoding<=2&&length>0&&length<=rawLength+1024u);
  if(x||y||w!=width_||h!=height_||!validLength||sid!=s.sessionId||page>=s.resultingPageCount||
     !(s.requiredPageBitmap&(1u<<page))||(s.receivedPageBitmap&(1u<<page))||expected!=s.pageCrc[page]||generation!=s.candidateCollectionGeneration||p[31]){
    const bool crcMatches = page < kMaxPagesPerCollection && expected == s.pageCrc[page];
    LOG_ERR("SCV5","begin page mismatch xy=%u,%u wh=%u,%u len=%lu sid=%u page=%u/%u req=0x%X recv=0x%X crc=%u gen=%lu/%lu reserved=%u",
      static_cast<unsigned>(x),static_cast<unsigned>(y),static_cast<unsigned>(w),static_cast<unsigned>(h),static_cast<unsigned long>(length),sid==s.sessionId?1u:0u,
      static_cast<unsigned>(page),static_cast<unsigned>(s.resultingPageCount),static_cast<unsigned>(s.requiredPageBitmap),static_cast<unsigned>(s.receivedPageBitmap),
      crcMatches?1u:0u,static_cast<unsigned long>(generation),static_cast<unsigned long>(s.candidateCollectionGeneration),static_cast<unsigned>(p[31]));return false;}
  if(!Store::pagePath(s.collectionId,s.candidateBank,page,pagePath_,sizeof(pagePath_)))return false;
  if(Storage.exists(pagePath_)&&!Storage.remove(pagePath_)&&Storage.exists(pagePath_))return false;
  const char* receivePath=pagePath_;
  if(encoding){
    const int chars=std::snprintf(encodedPath_,sizeof(encodedPath_),"%s.rx",pagePath_);
    if(chars<=0||chars>=static_cast<int>(sizeof(encodedPath_))){pagePath_[0]=0;return false;}
    if(Storage.exists(encodedPath_)&&!Storage.remove(encodedPath_)&&Storage.exists(encodedPath_)){pagePath_[0]=0;encodedPath_[0]=0;return false;}
    receivePath=encodedPath_;
  }
  if(!Storage.ensureDirectoryExists(Store::kDirectory)||!Storage.openFileForWrite("SCV5",receivePath,pageFile_)){
    pagePath_[0]=0;encodedPath_[0]=0;return false;
  }
  pageOpen_=true;pageIndex_=page;expectedCrc_=expected;received_=0;encodedLength_=length;
  encoding_=encoding;nextSeq_=0;rawCrc_=0xFFFFFFFFu;return true;
}
bool Transfer::data(const uint8_t* p,uint16_t n){
  if(!pageOpen_){LOG_ERR("SCV5","data without open page");return false;}
  const uint16_t seq=n>=3?get16(p+1):0;
  if(n<3||seq!=nextSeq_){LOG_ERR("SCV5","data sequence expected=%u got=%u len=%u",static_cast<unsigned>(nextSeq_),static_cast<unsigned>(seq),static_cast<unsigned>(n));closePage(true);return false;}
  const size_t bytes=n-3;const uint32_t total=encodedLength_;
  if(received_+bytes>total){LOG_ERR("SCV5","data overflow received=%lu bytes=%u total=%lu",static_cast<unsigned long>(received_),static_cast<unsigned>(bytes),static_cast<unsigned long>(total));closePage(true);return false;}
  const size_t written=pageFile_.write(p+3,bytes);
  if(written!=bytes){LOG_ERR("SCV5","page write short seq=%u wanted=%u wrote=%u",static_cast<unsigned>(seq),static_cast<unsigned>(bytes),static_cast<unsigned>(written));closePage(true);return false;}
  if(encoding_==0)rawCrc_=rawUpdate(rawCrc_,p+3,bytes);received_+=bytes;++nextSeq_;return true;
}
bool Transfer::inflateToPage(HalFile& input,uint32_t offset,uint32_t length,bool zlibWrapped,bool pngScanlines){
  if(!input.seek(offset))return false;
  FileFillContext context{};context.file=&input;context.remaining=length;
  DecodeScratchLoan scratchLoan;
  InflateStream inflater;
  if(!inflater.init(true)){
    LOG_ERR("SCV5","inflate init failed scratch=%u",scratchLoan.active()?1u:0u);
    return false;
  }
  inflater.setFill(fillInflate,&context);if(zlibWrapped)inflater.setZlibWrapped();
  if(Storage.exists(pagePath_)&&!Storage.remove(pagePath_)&&Storage.exists(pagePath_))return false;
  HalFile output;if(!Storage.openFileForWrite("SCV5",pagePath_,output))return false;
  const uint32_t rawLength=uint32_t((width_+7u)/8u)*height_;
  uint32_t producedTotal=0;uint32_t crc=0xFFFFFFFFu;bool ok=true;
  if(pngScanlines){
    const size_t rowBytes=(width_+7u)/8u;uint8_t row[100];
    if(rowBytes+1u>sizeof(row))ok=false;
    for(uint16_t y=0;ok&&y<height_;++y){
      if(!inflater.read(row,rowBytes+1u)||row[0]!=0){ok=false;break;}
      if(output.write(row+1,rowBytes)!=rowBytes){ok=false;break;}
      crc=rawUpdate(crc,row+1,rowBytes);producedTotal+=rowBytes;
    }
  }else{
    uint8_t buffer[1024];
    while(ok&&producedTotal<rawLength){
      size_t wanted=rawLength-producedTotal;if(wanted>sizeof(buffer))wanted=sizeof(buffer);
      size_t made=0;const auto status=inflater.readAtMost(buffer,wanted,&made);
      if(status==InflateStream::Status::Error||!made){ok=false;break;}
      if(output.write(buffer,made)!=made){ok=false;break;}
      crc=rawUpdate(crc,buffer,made);producedTotal+=made;
      if(status==InflateStream::Status::Done&&producedTotal!=rawLength){ok=false;break;}
    }
  }
  uint8_t extra=0;size_t extraCount=0;
  if(ok){
    const auto finalStatus=inflater.readAtMost(&extra,1,&extraCount);
    ok=finalStatus==InflateStream::Status::Done&&extraCount==0&&context.remaining==0&&
       producedTotal==rawLength&&(crc^0xFFFFFFFFu)==expectedCrc_;
  }
  output.flush();output.close();
  if(!ok&&Storage.exists(pagePath_))Storage.remove(pagePath_);
  return ok;
}
bool Transfer::decodeEncodedPage(){
  HalFile input;if(!Storage.openFileForRead("SCV5",encodedPath_,input)||input.size()!=encodedLength_){
    if(input)input.close();return false;
  }
  bool ok=false;
  if(encoding_==2){
    ok=inflateToPage(input,0,encodedLength_,false,false);
  }else if(encoding_==1){
    static constexpr uint8_t signature[8]={0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
    uint8_t bytes[13];uint8_t header[8];
    if(readExact(input,bytes,8)&&std::memcmp(bytes,signature,8)==0&&readExact(input,header,8)&&
       getBe32(header)==13&&std::memcmp(header+4,"IHDR",4)==0&&readExact(input,bytes,13)&&
       getBe32(bytes)==width_&&getBe32(bytes+4)==height_&&bytes[8]==1&&bytes[9]==0&&
       bytes[10]==0&&bytes[11]==0&&bytes[12]==0&&input.seekCur(4)){
      uint32_t idatOffset=0,idatLength=0;bool sawEnd=false,valid=true;
      while(valid&&input.position()+8<=input.size()){
        if(!readExact(input,header,8)){valid=false;break;}
        const uint32_t chunkLength=getBe32(header);
        if(chunkLength>encodedLength_||input.position()+chunkLength+4u>input.size()){valid=false;break;}
        if(std::memcmp(header+4,"IDAT",4)==0){
          if(idatLength){valid=false;break;}
          idatOffset=static_cast<uint32_t>(input.position());idatLength=chunkLength;
        }else if(std::memcmp(header+4,"IEND",4)==0){
          if(chunkLength){valid=false;break;}sawEnd=true;
        }
        if(!input.seekCur(static_cast<int64_t>(chunkLength)+4)){valid=false;break;}
        if(sawEnd)break;
      }
      if(valid&&sawEnd&&input.position()==input.size()&&idatLength)
        ok=inflateToPage(input,idatOffset,idatLength,true,true);
    }
  }
  input.close();return ok;
}
bool Transfer::verifyPage(const char* path,uint32_t expected)const{HalFile f;if(!Storage.openFileForRead("SCV5",path,f)||f.size()!=uint32_t((width_+7u)/8u)*height_){if(f)f.close();return false;}uint8_t b[1024];uint32_t c=0xFFFFFFFFu;size_t left=f.size();while(left){size_t want=left<sizeof(b)?left:sizeof(b);if(f.read(b,want)!=int(want)){f.close();return false;}c=rawUpdate(c,b,want);left-=want;}f.close();return(c^0xFFFFFFFFu)==expected;}
bool Transfer::end(const uint8_t* p,uint16_t n){
  if(!pageOpen_){LOG_ERR("SCV5","end without open page");return false;}
  if(n!=2){LOG_ERR("SCV5","end length=%u",static_cast<unsigned>(n));closePage(true);return false;}
  if(pageFile_){pageFile_.flush();pageFile_.close();}pageOpen_=false;
  if(received_!=encodedLength_){LOG_ERR("SCV5","page %u short received=%lu total=%lu",static_cast<unsigned>(pageIndex_),static_cast<unsigned long>(received_),static_cast<unsigned long>(encodedLength_));closePage(true);return false;}
  if(encoding_==0){
    const uint32_t actualCrc=rawCrc_^0xFFFFFFFFu;
    if(actualCrc!=expectedCrc_){LOG_ERR("SCV5","page %u stream crc expected=%08lx actual=%08lx",static_cast<unsigned>(pageIndex_),static_cast<unsigned long>(expectedCrc_),static_cast<unsigned long>(actualCrc));closePage(true);return false;}
  }else if(!decodeEncodedPage()){
    LOG_ERR("SCV5","page %u decode failed encoding=%u bytes=%lu",static_cast<unsigned>(pageIndex_),static_cast<unsigned>(encoding_),static_cast<unsigned long>(encodedLength_));closePage(true);return false;
  }
  if(!verifyPage(pagePath_,expectedCrc_)){LOG_ERR("SCV5","page %u persisted crc verification failed",static_cast<unsigned>(pageIndex_));closePage(true);return false;}
  SessionJournal s{};
  if(!store_.loadNewestSession(s)){LOG_ERR("SCV5","page %u session reload failed",static_cast<unsigned>(pageIndex_));closePage(true);return false;}
  if(s.receivedPageBitmap&(1u<<pageIndex_)){LOG_ERR("SCV5","page %u already received bitmap=%04x",static_cast<unsigned>(pageIndex_),static_cast<unsigned>(s.receivedPageBitmap));closePage(true);return false;}
  s.receivedPageBitmap|=uint16_t(1u<<pageIndex_);if(s.receivedPageBitmap==s.requiredPageBitmap)s.state=SessionState::Ready;
  if(!store_.checkpointSession(s)){LOG_ERR("SCV5","page %u checkpoint failed bitmap=%04x",static_cast<unsigned>(pageIndex_),static_cast<unsigned>(s.receivedPageBitmap));closePage(true);return false;}
  LOG_INF("SCV5","page %u durable encoding=%u bytes=%lu bitmap=%04x",static_cast<unsigned>(pageIndex_),static_cast<unsigned>(encoding_),static_cast<unsigned long>(encodedLength_),static_cast<unsigned>(s.receivedPageBitmap));
  if(encodedPath_[0]&&Storage.exists(encodedPath_))Storage.remove(encodedPath_);
  ++pagesThisConnection_;pagePath_[0]=0;encodedPath_[0]=0;received_=0;encodedLength_=0;encoding_=0;return true;
}

bool Transfer::commit(const uint8_t* p,uint16_t n){
  if(pageOpen_||n!=18||!packetCrc(p,n))return false;SessionJournal s{};if(!store_.loadNewestSession(s)||get64(p+1)!=s.sessionId||get32(p+9)!=s.candidateCollectionGeneration||s.state!=SessionState::Ready||s.receivedPageBitmap!=s.requiredPageBitmap||p[13]>=s.resultingPageCount)return false;
  for(uint8_t i=0;i<s.resultingPageCount;++i){char path[80];if(!Store::pagePath(s.collectionId,s.candidateBank,i,path,sizeof(path))||!verifyPage(path,s.pageCrc[i]))return false;}
  CollectionManifest manifest{};manifest.bank=s.candidateBank;manifest.pageCount=s.resultingPageCount;manifest.activePage=p[13];manifest.width=width_;manifest.height=height_;manifest.collectionId=s.collectionId;manifest.generation=s.candidateCollectionGeneration;for(uint8_t i=0;i<s.resultingPageCount;++i)manifest.pageCrc[i]=s.pageCrc[i];if(!store_.writeManifestVerified(manifest))return false;
  RootIndex root{};const bool have=store_.loadNewestRoot(root);
  if (have && root.generation == s.baseRootGeneration + 1u) {
    for (uint8_t i=0;i<root.entryCount;++i) if (root.entries[i].collectionId==s.collectionId &&
        root.entries[i].expectedGeneration==s.candidateCollectionGeneration) return store_.clearSessionRecords();
  }
  if((have?root.generation:0)!=s.baseRootGeneration)return false;
  if(!have){root={};root.entryCount=0;root.totalPublishedPages=0;}
  int found=-1;for(uint8_t i=0;i<root.entryCount;++i)if(root.entries[i].collectionId==s.collectionId)found=i;
  if(s.mode==SessionMode::Create){if(found>=0||root.entryCount>=kMaxCollections)return false;found=root.entryCount++;RootEntry& e=root.entries[found];e.collectionId=s.collectionId;e.flags=kEnabled;e.order=uint8_t(found);char name[kMaxNameBytes+1];const int chars=std::snprintf(name,sizeof(name),"Collection %lu",static_cast<unsigned long>(s.collectionId));if(chars<=0||chars>kMaxNameBytes)return false;e.nameLength=uint8_t(chars);std::memcpy(e.name,name,e.nameLength);root.totalPublishedPages+=s.resultingPageCount;}else{if(found<0)return false;CollectionManifest old{};if(!store_.loadCollectionManifest(s.collectionId,s.baseCollectionGeneration,old))return false;root.totalPublishedPages=uint16_t(root.totalPublishedPages-old.pageCount+s.resultingPageCount);}
  root.entries[found].expectedGeneration=s.candidateCollectionGeneration;root.generation=s.baseRootGeneration+1;if(!root.generation)return false;if(!root.selectedCollectionId)root.selectedCollectionId=s.collectionId;
  Selection selection{}, prior{}; selection.rootGeneration=root.generation;
  selection.collectionId=root.selectedCollectionId;
  for(uint8_t i=0;i<root.entryCount;++i)if(root.entries[i].collectionId==root.selectedCollectionId)
    selection.collectionGeneration=root.entries[i].expectedGeneration;
  if(root.selectedCollectionId==s.collectionId) selection.pageIndex=p[13];
  else {
    CollectionManifest selectedManifest{};
    const bool preserve=store_.loadSelection(prior)&&prior.rootGeneration==s.baseRootGeneration&&
      prior.collectionId==selection.collectionId&&prior.collectionGeneration==selection.collectionGeneration&&
      store_.loadCollectionManifest(selection.collectionId,selection.collectionGeneration,selectedManifest)&&
      prior.pageIndex<selectedManifest.pageCount;
    if(preserve){selection.pageIndex=prior.pageIndex;selection.rotationMode=prior.rotationMode;}
  }
  // Selection is advisory until the matching root is published. Root publication remains the
  // single atomic visibility point; a power loss before it leaves the prior root authoritative.
  if(!store_.writeSelectionVerified(selection)||!store_.publishRootVerified(root))return false;
  return store_.clearSessionRecords();
}
bool Transfer::abort(const uint8_t* p,uint16_t n){if(pageOpen_||n!=13||!packetCrc(p,n))return false;return store_.abortSession(get64(p+1));}
bool Transfer::setEnabled(const uint8_t* p,uint16_t n){
  if(pageOpen_||n!=14||!packetCrc(p,n)||p[9]>1)return false;
  SessionJournal session{};if(store_.loadNewestSession(session))return false;
  RootIndex root{};if(!store_.loadNewestRoot(root)||root.generation!=get32(p+5))return false;
  const uint32_t id=get32(p+1);int found=-1;uint8_t enabledCount=0;
  for(uint8_t i=0;i<root.entryCount;++i){if(root.entries[i].collectionId==id)found=i;if(root.entries[i].flags&kEnabled)++enabledCount;}
  if(found<0)return false;const bool enabled=p[9]!=0;const bool wasEnabled=(root.entries[found].flags&kEnabled)!=0;
  if(enabled==wasEnabled)return true;if(!enabled&&enabledCount<=1)return false;
  if(enabled)root.entries[found].flags|=kEnabled;else root.entries[found].flags&=~kEnabled;
  Selection selection{},prior{};root.generation+=1;if(!root.generation)return false;
  if(!enabled&&root.selectedCollectionId==id){
    for(uint8_t i=0;i<root.entryCount;++i)if(root.entries[i].flags&kEnabled){root.selectedCollectionId=root.entries[i].collectionId;break;}
  }
  selection.rootGeneration=root.generation;selection.collectionId=root.selectedCollectionId;
  for(uint8_t i=0;i<root.entryCount;++i)if(root.entries[i].collectionId==selection.collectionId)selection.collectionGeneration=root.entries[i].expectedGeneration;
  if(store_.loadSelection(prior)&&prior.collectionId==selection.collectionId&&prior.collectionGeneration==selection.collectionGeneration){selection.pageIndex=prior.pageIndex;selection.rotationMode=prior.rotationMode;}
  if(!store_.writeSelectionVerified(selection)||!store_.publishRootVerified(root))return false;
  LOG_INF("SCV5","collection %lu enabled=%u root=%lu",static_cast<unsigned long>(id),enabled?1u:0u,static_cast<unsigned long>(root.generation));return true;
}
}  // namespace sidecar::v5
