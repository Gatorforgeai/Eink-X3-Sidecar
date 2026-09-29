#pragma once

#include <stdint.h>
#include <stddef.h>

class SidecarSink;

// DESIGN INTENT: Parse bounded v1-v4 frame packets with fixed memory, strict dimensions,
// immutable transaction metadata, sequencing, and checksums; publish completion only after commit.

// Reassembles a 1bpp image sent as BEGIN / DATA... / END and blits it through a SidecarSink.
class SidecarFrameProtocol {
 public:
  static constexpr uint8_t kOpBegin = 0x01;
  static constexpr uint8_t kOpData  = 0x02;
  static constexpr uint8_t kOpEnd   = 0x03;
  static constexpr uint8_t kOpClear = 0x04;
  static constexpr uint8_t kOpBeginPage = 0x05;
  static constexpr uint8_t kOpBeginGroupPage = 0x08;

  explicit SidecarFrameProtocol(SidecarSink* sink);

  // Feed one transport write. Must be called from the task that owns the display: END presents,
  // and present() blocks on the panel waveform.
  void onWrite(const uint8_t* data, uint16_t len);

  // True between a valid BEGIN and its END.
  bool inTransfer() const;

  // Return true exactly once after a complete frame has been presented.
  bool consumeFrameComplete();

  // Abandon any transfer in progress. Call on disconnect so a half-sent frame cannot be
  // completed by the next client's bytes.
  void reset();

 private:
  void abortTransfer();

  static constexpr uint16_t kMaxRowBytes = 128;

  // BEGIN_PAGE adds bundle metadata; BEGIN_GROUP_PAGE adds a bounded catalog range.
  static constexpr uint8_t kMaxHdrBytes = 23;

  SidecarSink* sink_;
  bool frameComplete_;

  // Byte-by-byte parse state machine. In every state except Idle, the opcode
  // byte has already been consumed and hdrIdx_ counts how many subsequent
  // header bytes have been collected into rowBuf_.
  enum class State : uint8_t {
    Idle,         // Awaiting BEGIN or CLEAR opcode
    Begin,        // Collecting 13 bytes: u16 x, y, w, h, u8 format, u32 length
    TransferWait, // In a transfer; awaiting DATA or END opcode
    DataHdr,      // Collecting 2 bytes: u16 seq
    Data,         // Accumulating payload bytes into rowBuf_, blitting per row
    End,          // Collecting 1 byte: u8 refresh
    Clear,        // Collecting 1 byte: u8 colour
  };
  State state_;
  uint16_t hdrIdx_;

  // Transfer parameters parsed from BEGIN.
  uint16_t x_;
  uint16_t y_;
  uint16_t w_;
  uint16_t h_;
  uint8_t format_;
  bool pageMode_;
  bool groupMode_;
  uint8_t beginHeaderLength_;
  uint8_t pageIndex_;
  uint8_t pageCount_;
  uint8_t activePage_;
  uint8_t bundleNextPage_;
  uint8_t bundleActivePage_;
  uint8_t bundlePageCount_;
  uint8_t groupStart_;
  uint8_t groupCount_;
  uint8_t groupOffset_;
  uint8_t bundleGroupStart_;
  uint8_t bundleGroupCount_;
  bool bundleTransactionActive_;
  uint32_t expectedCrc_;
  uint32_t runningCrc_;
  uint32_t dataLength_;     // Total DATA payload bytes expected
  uint32_t dataReceived_;   // DATA payload bytes received so far
  uint16_t nextSeq_;        // Next expected DATA seq number
  uint16_t rowY_;           // y coordinate for the next blit
  uint16_t bufIdx_;         // Bytes accumulated in rowBuf_ for the current row

  // Accumulates one row of DATA payload at a time. Fixed size, never allocated.
  uint8_t rowBuf_[kMaxRowBytes];

  // Header bytes are collected HERE, never in rowBuf_. Rows span multiple DATA packets, so a
  // DATA header arriving mid-row would otherwise overwrite the first two bytes of that row.
  uint8_t hdrBuf_[kMaxHdrBytes];
};
