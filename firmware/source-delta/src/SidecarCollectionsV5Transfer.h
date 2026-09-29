#pragma once
#include "SidecarCollectionsV5Store.h"
#include <HalStorage.h>

namespace sidecar::v5 {
class Transfer {
 public:
  explicit Transfer(uint16_t width, uint16_t height);
  bool handles(uint8_t opcode) const;
  bool onPacket(const uint8_t* data, uint16_t length);
  void onDisconnect();
  bool inTransfer() const { return pageOpen_; }
 private:
  bool beginSession(const uint8_t*, uint16_t);
  bool beginPage(const uint8_t*, uint16_t);
  bool data(const uint8_t*, uint16_t);
  bool end(const uint8_t*, uint16_t);
  bool commit(const uint8_t*, uint16_t);
  bool abort(const uint8_t*, uint16_t);
  bool setEnabled(const uint8_t*, uint16_t);
  bool decodeEncodedPage();
  bool inflateToPage(HalFile& input, uint32_t offset, uint32_t length, bool zlibWrapped,
                     bool pngScanlines);
  void closePage(bool remove);
  bool verifyPage(const char*, uint32_t) const;
  uint16_t width_, height_;
  uint8_t pagesThisConnection_ = 0;
  bool pageOpen_ = false;
  uint16_t nextSeq_ = 0;
  uint32_t received_ = 0, encodedLength_ = 0, rawCrc_ = 0xFFFFFFFFu, expectedCrc_ = 0;
  uint8_t encoding_ = 0;
  uint8_t pageIndex_ = 0;
  char pagePath_[80] = {};
  char encodedPath_[84] = {};
  HalFile pageFile_;
  Store store_;
};
}  // namespace sidecar::v5
