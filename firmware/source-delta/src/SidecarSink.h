#pragma once

#include <stdint.h>
#include <stddef.h>

// DESIGN INTENT: Sidecar display implementations expose one-frame drawing plus recoverable
// multi-page storage/navigation without requiring callers to know panel or storage details.
// DONE-WHEN: v1 drawing remains valid and page bundles can stage, commit, abandon, and navigate.
// FAILS-WHEN: callers can publish an incomplete bundle or must allocate a second full framebuffer.

// Abstract display sink for the Eink Sidecar.
// Rotation is the sink's problem, not the caller's: callers pass a line index
// and a UTF-8 string, and the implementation maps that to the panel's current
// orientation (portrait, landscape, mirrored, etc.).
class SidecarSink {
 public:
  static constexpr uint8_t kMaxStoredPages = 16;
  static constexpr uint8_t kMaxGroupPages = 4;
  virtual ~SidecarSink() = default;

  // Prepare a blank frame. Nothing reaches the panel until present().
  // Callers must invoke beginFrame() before any drawCenteredLine() calls;
  // drawing before beginFrame() is undefined-but-safe.
  virtual void beginFrame() = 0;

  // Draw one line of UTF-8 text, horizontally centred, at line index `line`
  // (0 = first line from the top). Line spacing is the sink's business.
  // If `line` is >= maxLines(), the implementation clips. If `text` is nullptr
  // or empty, the implementation treats it as a blank line.
  virtual void drawCenteredLine(int line, const char* text) = 0;

  // Blit a 1-bit-per-pixel image into the frame at (x, y). Nothing reaches the panel until
  // present(). `data` is MSB-first, rows padded to whole bytes: each row occupies
  // ((w + 7) / 8) bytes and rows are contiguous. `data` must hold h of those rows.
  //
  // Coordinates and `data` are in the sink's own logical space, the same space width() and
  // height() describe. Callers never rotate; that is the sink's business (D-039).
  //
  // Returns false and draws NOTHING if data is null, w or h is 0, or the rectangle would fall
  // outside width() x height(). Partial blits are never performed - a caller that gets false
  // knows the frame is unchanged.
  virtual bool blit1bpp(uint16_t x, uint16_t y, uint16_t w, uint16_t h, const uint8_t* data) = 0;

  // Submit the framebuffer and block until the panel driver's refresh wait returns. The X3
  // driver exposes no hardware-success status beyond that completed call.
  virtual void present() = 0;

  // Protocol v2/v3 page bundles. V2 contains exactly two full-panel frames; v3 may contain
  // between one and kMaxStoredPages frames.
  // beginPageBundle selects the inactive on-disk bank, storePage persists the framebuffer
  // currently owned by the sink, and commitPageBundle publishes the bank only after both pages
  // have arrived and passed their transport checksum. The previous bundle remains readable after
  // any interrupted transfer. These methods never allocate another full framebuffer in RAM.
  virtual bool beginPageBundle(uint8_t pageCount, uint8_t activePage) = 0;
  virtual bool storePage(uint8_t pageIndex, uint32_t crc32) = 0;
  virtual bool commitPageBundle() = 0;
  virtual void abandonPageBundle() = 0;

  // Protocol v4 atomic group replacement. Implementations return false until their storage
  // format can keep the prior group readable through an interrupted update.
  // activePage is transaction metadata validated for consistency by the parser. A grouped
  // background refresh preserves the device's current durable selection while replacing content.
  virtual bool beginPageGroup(uint8_t catalogPageCount, uint8_t activePage,
                              uint8_t groupStart, uint8_t groupPageCount) { return false; }

  // Show the previous (-1), current (0), or next (+1) stored page. Returns false when no valid
  // stored bundle exists. A one-page bundle accepts restore/current operations; previous/next wrap
  // to that same page. Implementations persist a changed selection so navigation survives
  // deep sleep; showing the current page is used to restore visible content after a hard reset.
  virtual bool showStoredPage(int8_t delta) = 0;
  virtual bool hasStoredPages() = 0;
  // Optional staged handoff: existing implementations keep immediate commit behavior.
  virtual void deferBundleUntilCycle(bool enabled) {}
  virtual bool hasPendingBundle() const { return false; }
  virtual bool activatePendingBundle() { return false; }
  virtual bool backgroundReceptionSafe() const { return false; }
  virtual bool atLastStoredPage() const { return false; }

  // Geometry in the sink's own logical coordinate space.
  virtual uint16_t width() const = 0;
  virtual uint16_t height() const = 0;

  // Bytes per row for a full-width 1bpp image in this sink's logical space: (width() + 7) / 8.
  // The framing layer sizes its single row buffer from this and never allocates per frame.
  virtual uint16_t rowBytes() const = 0;

  // How many lines drawCenteredLine() can fit before running off the panel.
  virtual int maxLines() const = 0;
};

// Defined by exactly one platform translation unit.
// Returns nullptr if the display is not ready. Callers MUST null-check.
SidecarSink* sidecarSinkInstance();
