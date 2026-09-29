#include "SidecarSink.h"

#ifndef SIDECAR_LINK
SidecarSink* sidecarSinkInstance() { return nullptr; }
#else

#include <GfxRenderer.h>
#include <HalDisplay.h>
#include <HalStorage.h>
#include <Logging.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "SidecarCollectionsV5Store.h"
#include "fontIds.h"

extern HalDisplay display;
extern GfxRenderer renderer;

namespace {
// DESIGN INTENT:
// DONE-WHEN: v2/v3 stage checked full-screen bundles and v4 atomically replaces a small contiguous
// group while preserving the other pages; completed banks reload by button after sleep; v1 presents.
// INPUTS: 1..16 pages, valid group ranges, any valid active page, interrupted writes,
// corrupt/short files, storage exhaustion, and v1.
// FAILS-WHEN: an incomplete bundle replaces the valid one, a bad page displays, or RAM holds 2 frames.
constexpr int kFontId = NOTOSANS_18_FONT_ID;
constexpr int kTopMargin = 10;
constexpr int kSideMargin = 8;
constexpr size_t kLegacyManifestSize = 28;
constexpr size_t kManifestSize = 16 + 4 * SidecarSink::kMaxStoredPages + 4;
constexpr char kManifest0[] = "/.crosspoint/sidecar_pages_0.manifest";
constexpr char kManifest1[] = "/.crosspoint/sidecar_pages_1.manifest";
constexpr char kSelection[] = "/.crosspoint/sidecar_pages.selection";

struct Manifest {
  uint8_t bank = 0, count = 0, active = 0;
  uint16_t width = 0, height = 0;
  uint32_t generation = 0;
  uint32_t crc[SidecarSink::kMaxStoredPages] = {};
};

uint32_t crc32Update(uint32_t crc, const uint8_t* data, size_t len) {
  crc = ~crc;
  for (size_t i = 0; i < len; ++i) {
    crc ^= data[i];
    for (uint8_t bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
  }
  return ~crc;
}
uint32_t crc32RawUpdate(uint32_t raw, const uint8_t* data, size_t len) {
  for (size_t i = 0; i < len; ++i) {
    raw ^= data[i];
    for (uint8_t bit = 0; bit < 8; ++bit) raw = (raw >> 1) ^ (0xEDB88320u & (0u - (raw & 1u)));
  }
  return raw;
}
void put16(uint8_t* p, uint16_t v) { p[0] = v; p[1] = v >> 8; }
void put32(uint8_t* p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
uint16_t get16(const uint8_t* p) { return p[0] | (static_cast<uint16_t>(p[1]) << 8); }
uint32_t get32(const uint8_t* p) {
  return p[0] | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}
const char* manifestPath(uint8_t bank) { return bank ? kManifest1 : kManifest0; }
void pagePath(uint8_t bank, uint8_t page, char* out, size_t size) {
  std::snprintf(out, size, "/.crosspoint/sidecar_pages_%u_%u.bin", bank, page);
}
int computeMaxLines(int h, int lineHeight) {
  return lineHeight > 0 && h > kTopMargin ? (h - kTopMargin) / lineHeight : 0;
}

class SidecarSinkX3 : public SidecarSink {
 public:
  SidecarSinkX3(HalDisplay& d, GfxRenderer& r)
      : display_(d), renderer_(r), savedOrientation_(GfxRenderer::Orientation::Portrait) {}

  void beginFrame() override {
    if (stagingActive_) {
      if (stagingFile_) stagingFile_.close();
      stagingBytes_ = 0;
      char path[64]; pagePath(staging_.bank, stagingNext_, path, sizeof(path));
      stagingFileOk_ = Storage.openFileForWrite("SIDECAR", path, stagingFile_);
      return; // Incoming bundle rows never touch the displayed framebuffer.
    }
    if (!orientationSaved_) { savedOrientation_ = renderer_.getOrientation(); orientationSaved_ = true; }
    renderer_.setOrientation(GfxRenderer::Orientation::Portrait);
    display_.clearScreen(0xFF);
    if (!geometryLogged_) {
      char b[128];
      std::snprintf(b, sizeof(b), "geometry logical w=%d h=%d lineHeight=%d maxLines=%d prevOrientation=%d",
                    renderer_.getScreenWidth(), renderer_.getScreenHeight(), renderer_.getLineHeight(kFontId),
                    maxLines(), static_cast<int>(savedOrientation_));
      LOG_INF("SINK", "%s", b); geometryLogged_ = true;
    }
  }

  void drawCenteredLine(int line, const char* text) override {
    if (line < 0 || line >= maxLines()) return;
    const int lh = renderer_.getLineHeight(kFontId);
    if (lh <= 0) return;
    if (!text || !text[0]) {
      renderer_.drawCenteredText(kFontId, kTopMargin + line * lh, " ", true);
      return;
    }
    const auto rows = renderer_.wrappedText(kFontId, text, static_cast<int>(width()) - 2 * kSideMargin,
                                            maxLines() - line);
    for (const auto& row : rows) {
      if (line >= maxLines()) break;
      renderer_.drawCenteredText(kFontId, kTopMargin + line++ * lh, row.c_str(), true);
    }
  }

  bool blit1bpp(uint16_t x, uint16_t y, uint16_t w, uint16_t h, const uint8_t* data) override {
    if (!data || !w || !h || static_cast<uint32_t>(x) + w > width() ||
        static_cast<uint32_t>(y) + h > height()) return false;
    if (stagingActive_) {
      const size_t bytes = static_cast<size_t>(rowBytes()) * h;
      if (!stagingFileOk_ || x != 0 || w != width() ||
          static_cast<size_t>(y) * rowBytes() != stagingBytes_) return false;
      if (stagingFile_.write(data, bytes) != bytes) { stagingFileOk_ = false; return false; }
      stagingBytes_ += bytes;
      return true;
    }
    display_.drawImage(data, x, y, w, h);
    if (!blitLoggedOnce_) {
      LOG_INF("SINK", "blit x=%u y=%u w=%u h=%u logicalW=%u logicalH=%u panelW=%u panelH=%u",
              x, y, w, h, width(), height(), display_.getDisplayWidth(), display_.getDisplayHeight());
      blitLoggedOnce_ = true;
    }
    return true;
  }

  void present() override {
    display_.displayBuffer(HalDisplay::FAST_REFRESH);
    display_.waitRefreshComplete();
    restoreOrientation();
  }

  bool beginPageBundle(uint8_t count, uint8_t active) override {
    if (count == 0 || count > kMaxStoredPages || active >= count) return false;
    if (ready_) return false;  // A verified deferred catalog owns the inactive bank until activation.
    deferCommit_ = deferNextBundle_;
    deferNextBundle_ = false;
    ready_ = false;
    Manifest current;
    // This runs while the phone is streaming writes. Validate only the small manifest records
    // here; full page-file CRC checks remain mandatory at commit and before display.
    const bool haveCurrent = activeCacheValid_ ? (current = activeCache_, true) : loadNewestManifest(current);
    if (haveCurrent && !activeCacheValid_) {
      applySelection(current);
      activeCache_ = current;
      activeCacheValid_ = true;
    }
    staging_ = Manifest{};
    staging_.bank = haveCurrent ? static_cast<uint8_t>(current.bank ^ 1u) : 0;
    staging_.count = count; staging_.active = active; staging_.width = width(); staging_.height = height();
    if (deferCommit_ && haveCurrent) {
      // Preserve the durable local selection. If the replacement shrinks the catalog, clamp to
      // its last valid page instead of jumping to the phone's stale selection snapshot.
      staging_.active = current.active < count ? current.active : static_cast<uint8_t>(count - 1u);
    }
    staging_.generation = haveCurrent ? current.generation + 1u : 1u;
    if (!clearInactiveBank(staging_.bank)) {
      LOG_ERR("SIDECAR", "bundle stage: failed to reclaim inactive bank=%u", staging_.bank);
      return false;
    }
    stagingNext_ = 0; stagingEnd_ = count; stagingActive_ = true;
    LOG_INF("SIDECAR", "bundle stage bank=%u generation=%lu pages=%u active=%u", staging_.bank,
            static_cast<unsigned long>(staging_.generation), count, active);
    return true;
  }

  bool beginPageGroup(uint8_t catalogCount, uint8_t active, uint8_t groupStart,
                      uint8_t groupCount) override {
    if (catalogCount == 0 || catalogCount > kMaxStoredPages || active >= catalogCount ||
        groupCount == 0 || groupCount > kMaxGroupPages || groupStart >= catalogCount ||
        static_cast<uint16_t>(groupStart) + groupCount > catalogCount) return false;
    if (ready_) return false;  // Do not overwrite a verified group waiting at the cycle boundary.
    Manifest current;
    const bool haveCurrent = activeCacheValid_ ? (current = activeCache_, true)
                                                : loadNewestManifest(current);
    if (!haveCurrent || current.count != catalogCount) return false;
    applySelection(current);
    activeCache_ = current;
    activeCacheValid_ = true;
    deferCommit_ = deferNextBundle_;
    deferNextBundle_ = false;
    staging_ = current;
    staging_.bank = static_cast<uint8_t>(current.bank ^ 1u);
    // Preserve the X3's current position while content behind it is replaced.
    staging_.active = current.active;
    staging_.generation = current.generation + 1u;
    if (!clearInactiveBank(staging_.bank)) {
      LOG_ERR("SIDECAR", "group stage: failed to reclaim inactive bank=%u", staging_.bank);
      return false;
    }
    stagingNext_ = groupStart;
    stagingEnd_ = static_cast<uint8_t>(groupStart + groupCount);
    stagingActive_ = true;
    ready_ = false;

    // Populate the inactive bank with the unchanged pages before accepting replacement bytes.
    // If power or storage fails here the current manifest still points exclusively at the old bank.
    for (uint8_t page = 0; page < catalogCount; ++page) {
      if (page >= groupStart && page < stagingEnd_) continue;
      char source[64], destination[64];
      pagePath(current.bank, page, source, sizeof(source));
      pagePath(staging_.bank, page, destination, sizeof(destination));
      if (!copyPageFile(source, destination, current.crc[page])) {
        abandonPageBundle();
        LOG_ERR("SIDECAR", "%s", "group stage: unchanged page copy failed");
        return false;
      }
    }
    LOG_INF("SIDECAR", "group stage bank=%u generation=%lu range=%u..%u catalog=%u",
            staging_.bank, static_cast<unsigned long>(staging_.generation), groupStart,
            static_cast<unsigned>(stagingEnd_ - 1u), catalogCount);
    return true;
  }

  bool storePage(uint8_t page, uint32_t expectedCrc) override {
    if (!stagingActive_ || page != stagingNext_ || page >= stagingEnd_) return false;
    const size_t size = display_.getBufferSize();
    const size_t written = stagingBytes_;
    if (stagingFile_) { stagingFile_.flush(); stagingFile_.close(); }
    if (!stagingFileOk_ || written != size) return false;
    char path[64]; pagePath(staging_.bank, page, path, sizeof(path));
    if (!verifyPageFile(path, expectedCrc)) return false;
    staging_.crc[page] = expectedCrc; ++stagingNext_;
    LOG_INF("SIDECAR", "bundle page stored bank=%u page=%u bytes=%u", staging_.bank, page,
            static_cast<unsigned>(written));
    return true;
  }

  bool commitPageBundle() override {
    if (!stagingActive_ || stagingNext_ != stagingEnd_) return false;
    if (deferCommit_ && activeCacheValid_ && activeCache_.count >= 1) {
      ready_ = true;
      LOG_INF("SIDECAR", "bundle ready generation=%lu; waiting for cycle boundary",
              static_cast<unsigned long>(staging_.generation));
      return true;
    }
    Manifest previous;
    const bool havePrevious = loadNewestManifest(previous);
    if (havePrevious) applySelection(previous);
    Manifest committed = staging_;
    if (!loadPage(committed, committed.active)) {
      if (havePrevious && !loadPage(previous, previous.active)) {
        activeCacheValid_ = false;
        LOG_ERR("SIDECAR", "%s", "bundle load and rollback both failed; suppressing presentation");
      }
      return false;
    }
    if (!writeSelection(committed) || !writeManifest(committed)) {
      // writeManifest() verifies after closing the file. A storage layer may report a failed
      // write/verification after a complete, valid newer manifest reached the media. Remove the
      // candidate manifest before reloading so failed publication can never supersede `previous`.
      if (Storage.exists(manifestPath(committed.bank)) &&
          !Storage.remove(manifestPath(committed.bank))) {
        LOG_ERR("SIDECAR", "%s", "failed candidate manifest could not be removed");
        activeCacheValid_ = false;
        return false;
      }
      if (havePrevious && !loadPage(previous, previous.active)) {
        activeCacheValid_ = false;
        LOG_ERR("SIDECAR", "%s", "bundle publish rollback failed; suppressing presentation");
      }
      return false;
    }
    activeCache_ = committed;
    activeCacheValid_ = true;
    stagingActive_ = false; stagingNext_ = 0; stagingEnd_ = 0; ready_ = false; deferCommit_ = false;
    present();
    LOG_INF("SIDECAR", "bundle committed bank=%u generation=%lu active=%u/%u", committed.bank,
            static_cast<unsigned long>(committed.generation), committed.active + 1, committed.count);
    return true;
  }

  void deferBundleUntilCycle(bool enabled) override { deferNextBundle_ = enabled; }
  bool hasPendingBundle() const override { return ready_; }
  bool backgroundReceptionSafe() const override { return deferCommit_ && stagingActive_; }
  bool atLastStoredPage() const override {
    bool v5Last = false;
    if (v5PositionIsLast(v5Last)) return v5Last;
    return activeCacheValid_ && activeCache_.active + 1 == activeCache_.count;
  }
  bool activatePendingBundle() override {
    if (!ready_) return false;
    // Local turns may continue after staging completes. Carry the latest durable selection into
    // the pending bank at the handoff boundary instead of presenting the begin-time snapshot.
    if (activeCacheValid_) {
      staging_.active = activeCache_.active < staging_.count
          ? activeCache_.active
          : static_cast<uint8_t>(staging_.count - 1u);
    }
    deferCommit_ = false;
    return commitPageBundle();
  }

  void abandonPageBundle() override {
    // A fully verified deferred catalog is no longer an in-progress parser transaction. Preserve
    // it across disconnects, overflow recovery, or stray packets until the cycle boundary can
    // activate it. It has not been acknowledged to the phone and the old manifest remains live.
    if (ready_) {
      if (stagingFile_) stagingFile_.close();
      stagingFileOk_ = false;
      return;
    }
    if (stagingFile_) stagingFile_.close(); stagingFileOk_ = false; ready_ = false;
    deferCommit_ = false; deferNextBundle_ = false;
    stagingActive_ = false; stagingNext_ = 0; stagingEnd_ = 0;
    // A rejected transfer may have partially overwritten the live framebuffer. Reload the last
    // committed selection without refreshing so any later unrelated present cannot expose it.
    Manifest previous;
    if (loadNewestManifest(previous)) {
      applySelection(previous);
      if (loadPage(previous, previous.active)) {
        activeCache_ = previous;
        activeCacheValid_ = true;
      }
    }
  }

  bool showStoredPage(int8_t delta) override {
    if (delta < -1 || delta > 1) return false;
    if (showV5StoredPage(delta)) return true;
    Manifest m;
    if (activeCacheValid_) {
      m = activeCache_;
    } else {
      if (!loadNewestManifest(m) || m.count == 0) return false;
      applySelection(m);
      activeCache_ = m;
      activeCacheValid_ = true;
    }
    if (m.count == 0) return false;
    const uint8_t previous = m.active;
    const uint8_t next = static_cast<uint8_t>((m.active + delta + m.count) % m.count);
    if (!loadPage(m, next)) {
      if (!loadPage(m, previous)) {
        activeCacheValid_ = false;
        LOG_ERR("SIDECAR", "%s", "page load and rollback both failed; suppressing presentation");
      }
      return false;
    }
    m.active = next;
    if (delta != 0 && !writeSelection(m)) {
      // Selection did not become durable. Restore the prior page in RAM so a later unrelated
      // refresh cannot expose the uncommitted navigation target.
      if (!loadPage(m, previous)) {
        activeCacheValid_ = false;
        LOG_ERR("SIDECAR", "%s", "selection rollback failed; suppressing presentation");
      }
      return false;
    }
    activeCache_ = m;
    present();
    LOG_INF("SIDECAR", "bundle page shown %u/%u", m.active + 1, m.count);
    return true;
  }

  bool hasStoredPages() override {
    if (hasV5StoredPages()) return true;
    Manifest m;
    if (!loadNewestManifest(m) || m.count == 0) return false;
    applySelection(m);
    activeCache_ = m;
    activeCacheValid_ = true;
    return true;
  }
  uint16_t width() const override { return display_.getDisplayWidth(); }
  uint16_t height() const override { return display_.getDisplayHeight(); }
  uint16_t rowBytes() const override { return (width() + 7) / 8; }
  int maxLines() const override { return computeMaxLines(renderer_.getScreenHeight(), renderer_.getLineHeight(kFontId)); }

 private:
  bool loadV5Page(const sidecar::v5::CollectionManifest& manifest, uint8_t page) {
    if (page >= manifest.pageCount || manifest.width != width() || manifest.height != height()) return false;
    char path[80];
    if (!sidecar::v5::Store::pagePath(manifest.collectionId, manifest.bank, page, path, sizeof(path)))
      return false;
    HalFile file;
    if (!Storage.openFileForRead("SCV5", path, file)) return false;
    uint8_t* frame = display_.getFrameBuffer(); const size_t size = display_.getBufferSize();
    if (!frame || !size || file.size() != size) { file.close(); return false; }
    size_t offset = 0; uint32_t rawCrc = 0xFFFFFFFFu;
    while (offset < size) {
      const size_t wanted = (size - offset) < sizeof(verifyChunk_) ? (size - offset) : sizeof(verifyChunk_);
      const size_t n = file.read(frame + offset, wanted);
      if (n != wanted) { file.close(); return false; }
      rawCrc = crc32RawUpdate(rawCrc, frame + offset, n); offset += n;
    }
    file.close();
    return (rawCrc ^ 0xFFFFFFFFu) == manifest.pageCrc[page];
  }

  bool loadV5Catalog(sidecar::v5::RootIndex& root, sidecar::v5::Selection& selection,
                     bool& selectionValid, uint16_t& totalPages, uint16_t& selectedOrdinal) const {
    sidecar::v5::Store store;
    if (!store.loadNewestRoot(root) || root.entryCount == 0) return false;
    selectionValid = store.loadSelection(selection) && selection.rootGeneration == root.generation;
    totalPages = 0; selectedOrdinal = 0;
    bool foundSelection = false;
    for (uint8_t i = 0; i < root.entryCount; ++i) {
      const auto& entry = root.entries[i];
      if (!(entry.flags & sidecar::v5::kEnabled)) continue;
      sidecar::v5::CollectionManifest manifest{};
      if (!store.loadCollectionManifest(entry.collectionId, entry.expectedGeneration, manifest) ||
          manifest.pageCount == 0 || manifest.width != width() || manifest.height != height()) return false;
      if (selectionValid && selection.collectionId == entry.collectionId &&
          selection.collectionGeneration == manifest.generation && selection.pageIndex < manifest.pageCount) {
        selectedOrdinal = static_cast<uint16_t>(totalPages + selection.pageIndex);
        foundSelection = true;
      }
      totalPages = static_cast<uint16_t>(totalPages + manifest.pageCount);
    }
    selectionValid = selectionValid && foundSelection;
    return totalPages > 0;
  }

  bool locateV5Page(const sidecar::v5::RootIndex& root, uint16_t ordinal,
                    sidecar::v5::CollectionManifest& manifest, uint8_t& page) const {
    sidecar::v5::Store store; uint16_t base = 0;
    for (uint8_t i = 0; i < root.entryCount; ++i) {
      const auto& entry = root.entries[i];
      if (!(entry.flags & sidecar::v5::kEnabled)) continue;
      sidecar::v5::CollectionManifest candidate{};
      if (!store.loadCollectionManifest(entry.collectionId, entry.expectedGeneration, candidate)) return false;
      if (ordinal < base + candidate.pageCount) {
        manifest = candidate; page = static_cast<uint8_t>(ordinal - base); return true;
      }
      base = static_cast<uint16_t>(base + candidate.pageCount);
    }
    return false;
  }

  bool showV5StoredPage(int8_t delta) {
    sidecar::v5::RootIndex root{}; sidecar::v5::Selection selection{};
    bool selectionValid = false; uint16_t total = 0, current = 0;
    if (!loadV5Catalog(root, selection, selectionValid, total, current)) return false;
    if (!selectionValid) current = 0;
    const uint16_t next = static_cast<uint16_t>((static_cast<int32_t>(current) + delta + total) % total);
    sidecar::v5::CollectionManifest manifest{}; uint8_t page = 0;
    if (!locateV5Page(root, next, manifest, page) || !loadV5Page(manifest, page)) {
      LOG_ERR("SIDECAR", "v5 page load failed ordinal=%u total=%u", next, total); return false;
    }
    sidecar::v5::Selection updated{};
    updated.rotationMode = selectionValid ? selection.rotationMode : sidecar::v5::RotationMode::Automatic;
    updated.rootGeneration = root.generation; updated.collectionId = manifest.collectionId;
    updated.collectionGeneration = manifest.generation; updated.pageIndex = page;
    if ((!selectionValid || delta != 0) && !sidecar::v5::Store().writeSelectionVerified(updated)) {
      LOG_ERR("SIDECAR", "%s", "v5 selection persistence failed"); return false;
    }
    present();
    LOG_INF("SIDECAR", "v5 page shown collection=%lu generation=%lu page=%u/%u ordinal=%u/%u",
            static_cast<unsigned long>(manifest.collectionId), static_cast<unsigned long>(manifest.generation),
            static_cast<unsigned>(page + 1u), static_cast<unsigned>(manifest.pageCount),
            static_cast<unsigned>(next + 1u), static_cast<unsigned>(total));
    return true;
  }

  bool hasV5StoredPages() const {
    sidecar::v5::RootIndex root{}; sidecar::v5::Selection selection{};
    bool valid = false; uint16_t total = 0, selected = 0;
    return loadV5Catalog(root, selection, valid, total, selected);
  }

  bool v5PositionIsLast(bool& last) const {
    sidecar::v5::RootIndex root{}; sidecar::v5::Selection selection{};
    bool valid = false; uint16_t total = 0, selected = 0;
    if (!loadV5Catalog(root, selection, valid, total, selected)) return false;
    last = valid && selected + 1u == total; return true;
  }

  bool removeScratchFile(const char* path) {
    if (!Storage.exists(path)) return true;
    // SdFat can report a failed remove while its directory update has already reached the card.
    // Judge the durable postcondition, and retry transient failures before rejecting the bundle.
    for (uint8_t attempt = 0; attempt < 3; ++attempt) {
      Storage.remove(path);
      if (!Storage.exists(path)) return true;
      delay(5);
    }
    // Some damaged/stale FAT directory entries refuse unlink but still permit O_TRUNC. A zero-byte
    // inactive object holds no page clusters and is safe scratch: replacement pages overwrite it,
    // while an unused page or manifest remains invalid and cannot be published.
    HalFile truncated;
    if (Storage.openFileForWrite("SIDECAR", path, truncated)) {
      truncated.flush();
      truncated.close();
      HalFile check;
      if (Storage.openFileForRead("SIDECAR", path, check)) {
        const bool empty = check.size() == 0;
        check.close();
        if (empty) {
          LOG_INF("SIDECAR", "inactive-bank file truncated path=%s", path);
          return true;
        }
      }
    }
    // Last-resort recovery for a damaged long-file-name entry: move the orphan out of the
    // canonical page namespace. The remaining inactive files can then be reclaimed and this page
    // can be recreated. One orphan framebuffer costs far less than retaining an entire stale bank.
    for (uint8_t slot = 0; slot < kMaxStoredPages; ++slot) {
      char orphan[64];
      std::snprintf(orphan, sizeof(orphan), "/.crosspoint/sidecar_orphan_%u.bin", slot);
      if (Storage.exists(orphan)) continue;
      if (Storage.rename(path, orphan) && !Storage.exists(path)) {
        LOG_INF("SIDECAR", "inactive-bank file quarantined path=%s as=%s", path, orphan);
        return true;
      }
    }
    LOG_ERR("SIDECAR", "inactive-bank remove failed path=%s", path);
    return false;
  }

  bool clearInactiveBank(uint8_t bank) {
    // The inactive bank is unpublished scratch space. Remove its stale manifest first so a power
    // loss during reclamation can only leave the current published bank eligible for restoration.
    const char* manifest = manifestPath(bank);
    if (!removeScratchFile(manifest)) return false;
    for (uint8_t page = 0; page < kMaxStoredPages; ++page) {
      char path[64];
      pagePath(bank, page, path, sizeof(path));
      if (!removeScratchFile(path)) return false;
    }
    return true;
  }

  void restoreOrientation() {
    if (orientationSaved_) { renderer_.setOrientation(savedOrientation_); orientationSaved_ = false; }
  }

  bool readManifest(uint8_t bank, Manifest& out, bool verifyPages = true) {
    HalFile f;
    if (!Storage.openFileForRead("SIDECAR", manifestPath(bank), f)) return false;
    const size_t size = f.size();
    uint8_t b[kManifestSize] = {};
    if (size != kLegacyManifestSize && size != kManifestSize) { f.close(); return false; }
    const size_t n = f.read(b, size); f.close();
    if (n != size) return false;
    out = Manifest{};
    if (size == kLegacyManifestSize) {
      if (std::memcmp(b, "SPG2", 4) || b[4] != 1 || b[5] != bank ||
          crc32Update(0, b, 24) != get32(b + 24)) return false;
      if (b[6] != 2) return false;  // SPG2 contains CRC fields for exactly two pages.
      out.crc[0] = get32(b + 16); out.crc[1] = get32(b + 20);
    } else {
      if (std::memcmp(b, "SPG3", 4) || b[4] != 1 || b[5] != bank ||
          crc32Update(0, b, kManifestSize - 4) != get32(b + kManifestSize - 4)) return false;
      for (uint8_t page = 0; page < kMaxStoredPages; ++page) out.crc[page] = get32(b + 16 + page * 4);
    }
    out.bank = b[5]; out.count = b[6]; out.active = b[7]; out.width = get16(b + 8); out.height = get16(b + 10);
    out.generation = get32(b + 12);
    if (out.count == 0 || out.count > kMaxStoredPages || out.active >= out.count ||
        out.width != width() || out.height != height()) return false;
    if (verifyPages) {
      for (uint8_t page = 0; page < out.count; ++page) {
        char path[64]; pagePath(out.bank, page, path, sizeof(path));
        if (!verifyPageFile(path, out.crc[page])) return false;
      }
    }
    return true;
  }

  bool loadNewestManifest(Manifest& out, bool verifyPages = true) {
    Manifest a, b; const bool ha = readManifest(0, a, verifyPages), hb = readManifest(1, b, verifyPages);
    if (!ha && !hb) return false;
    const bool aIsNewer = ha && (!hb || static_cast<int32_t>(a.generation - b.generation) >= 0);
    out = aIsNewer ? a : b; return true;
  }

  bool writeManifest(const Manifest& m) {
    uint8_t b[kManifestSize] = {}; std::memcpy(b, "SPG3", 4); b[4] = 1; b[5] = m.bank; b[6] = m.count; b[7] = m.active;
    put16(b + 8, m.width); put16(b + 10, m.height); put32(b + 12, m.generation);
    for (uint8_t page = 0; page < kMaxStoredPages; ++page) put32(b + 16 + page * 4, m.crc[page]);
    put32(b + kManifestSize - 4, crc32Update(0, b, kManifestSize - 4));
    HalFile f; if (!Storage.openFileForWrite("SIDECAR", manifestPath(m.bank), f)) return false;
    const size_t written = f.write(b, sizeof(b)); f.flush(); f.close();
    if (written != sizeof(b)) return false;
    Manifest verified;
    if (!readManifest(m.bank, verified) || verified.bank != m.bank || verified.count != m.count ||
        verified.active != m.active || verified.generation != m.generation) return false;
    for (uint8_t page = 0; page < m.count; ++page) if (verified.crc[page] != m.crc[page]) return false;
    return true;
  }

  bool loadPage(const Manifest& m, uint8_t page) {
    if (page >= m.count) return false;
    char path[64]; pagePath(m.bank, page, path, sizeof(path));
    HalFile f;
    if (!Storage.openFileForRead("SIDECAR", path, f)) return false;
    uint8_t* frame = display_.getFrameBuffer(); const size_t size = display_.getBufferSize();
    if (!frame || !size || f.size() != size) { f.close(); return false; }
    size_t offset = 0;
    uint32_t rawCrc = 0xFFFFFFFFu;
    while (offset < size) {
      const size_t wanted = (size - offset) < sizeof(verifyChunk_) ? (size - offset) : sizeof(verifyChunk_);
      const size_t n = f.read(frame + offset, wanted);
      if (n != wanted) { f.close(); return false; }
      rawCrc = crc32RawUpdate(rawCrc, frame + offset, n);
      offset += n;
    }
    f.close();
    return (rawCrc ^ 0xFFFFFFFFu) == m.crc[page];
  }

  bool verifyPageFile(const char* path, uint32_t expectedCrc) {
    HalFile f;
    if (!Storage.openFileForRead("SIDECAR", path, f) || f.size() != display_.getBufferSize()) {
      if (f) f.close();
      return false;
    }
    // SD command overhead dominates with 256-byte reads. A fixed 2 KiB member buffer keeps
    // verification bounded while reducing a full X3 page from 205 reads to 26.
    uint8_t* chunk = verifyChunk_;
    size_t remaining = display_.getBufferSize();
    uint32_t rawCrc = 0xFFFFFFFFu;
    while (remaining > 0) {
      const size_t wanted = remaining < sizeof(verifyChunk_) ? remaining : sizeof(verifyChunk_);
      const size_t n = f.read(chunk, wanted);
      if (n != wanted) { f.close(); return false; }
      rawCrc = crc32RawUpdate(rawCrc, chunk, n);
      remaining -= n;
    }
    f.close();
    return (rawCrc ^ 0xFFFFFFFFu) == expectedCrc;
  }

  bool copyPageFile(const char* source, const char* destination, uint32_t expectedCrc) {
    HalFile in, out;
    if (!Storage.openFileForRead("SIDECAR", source, in) || in.size() != display_.getBufferSize()) {
      if (in) in.close();
      return false;
    }
    if (!Storage.openFileForWrite("SIDECAR", destination, out)) { in.close(); return false; }
    size_t remaining = display_.getBufferSize();
    bool ok = true;
    while (remaining > 0) {
      const size_t wanted = remaining < sizeof(verifyChunk_) ? remaining : sizeof(verifyChunk_);
      const size_t read = in.read(verifyChunk_, wanted);
      if (read != wanted || out.write(verifyChunk_, read) != read) { ok = false; break; }
      remaining -= read;
    }
    in.close(); out.flush(); out.close();
    // verifyPageFile reopens the closed destination, requires the exact persisted framebuffer
    // length, reads every byte, and recomputes CRC before this clone can become publishable.
    return ok && remaining == 0 && verifyPageFile(destination, expectedCrc);
  }

  bool writeSelection(const Manifest& m) {
    uint8_t b[12] = {'S','P','S','1',m.bank,m.active,0,0,0,0,0,0};
    put32(b + 6, m.generation); put16(b + 10, static_cast<uint16_t>(crc32Update(0, b, 10)));
    // The selection is advisory until its matching manifest is published. Reclaim or quarantine
    // a damaged old directory entry before replacing it; the old manifest still carries a safe
    // active-page fallback if power fails in this interval.
    if (!removeScratchFile(kSelection)) return false;
    HalFile f; if (!Storage.openFileForWrite("SIDECAR", kSelection, f)) return false;
    const size_t written = f.write(b, sizeof(b)); f.flush(); f.close();
    if (written != sizeof(b)) return false;
    HalFile check; if (!Storage.openFileForRead("SIDECAR", kSelection, check)) return false;
    uint8_t readback[sizeof(b)];
    const size_t n = check.read(readback, sizeof(readback)); const size_t size = check.size(); check.close();
    return n == sizeof(readback) && size == sizeof(readback) && !std::memcmp(b, readback, sizeof(b));
  }

  void applySelection(Manifest& m) {
    HalFile f; if (!Storage.openFileForRead("SIDECAR", kSelection, f)) return;
    uint8_t b[12]; const size_t n = f.read(b, sizeof(b)); f.close();
    if (n == sizeof(b) && !std::memcmp(b, "SPS1", 4) && get16(b + 10) == static_cast<uint16_t>(crc32Update(0, b, 10)) &&
        b[4] == m.bank && get32(b + 6) == m.generation && b[5] < m.count) m.active = b[5];
  }

  HalDisplay& display_; GfxRenderer& renderer_; bool geometryLogged_ = false;
  GfxRenderer::Orientation savedOrientation_; bool orientationSaved_ = false; bool blitLoggedOnce_ = false;
  HalFile stagingFile_;
  size_t stagingBytes_ = 0;
  bool stagingFileOk_ = false, deferNextBundle_ = false, deferCommit_ = false, ready_ = false;
  bool stagingActive_ = false; uint8_t stagingNext_ = 0, stagingEnd_ = 0; Manifest staging_;
  bool activeCacheValid_ = false; Manifest activeCache_;
  uint8_t verifyChunk_[2048] = {};
};
}  // namespace

SidecarSink* sidecarSinkInstance() { static SidecarSinkX3 instance(display, renderer); return &instance; }
#endif
