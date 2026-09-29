#include "SidecarCollectionsV5.h"

#include <cstring>

namespace sidecar::v5 {
namespace {

constexpr uint8_t kRootMagic[4] = {'S', 'C', 'I', '5'};
constexpr uint8_t kManifestMagic[4] = {'S', 'C', 'M', '5'};
constexpr uint8_t kSelectionMagic[4] = {'S', 'C', 'S', '5'};
constexpr uint8_t kSessionMagic[4] = {'S', 'C', 'T', '5'};
constexpr uint8_t kInventoryMagic[4] = {'S', 'I', 'V', '5'};

void put16(uint8_t* p, uint16_t v) { p[0] = v; p[1] = static_cast<uint8_t>(v >> 8); }
void put32(uint8_t* p, uint32_t v) {
  p[0] = v; p[1] = static_cast<uint8_t>(v >> 8); p[2] = static_cast<uint8_t>(v >> 16);
  p[3] = static_cast<uint8_t>(v >> 24);
}
void put64(uint8_t* p, uint64_t v) { put32(p, static_cast<uint32_t>(v)); put32(p + 4, static_cast<uint32_t>(v >> 32)); }
uint16_t get16(const uint8_t* p) { return p[0] | (static_cast<uint16_t>(p[1]) << 8); }
uint32_t get32(const uint8_t* p) {
  return p[0] | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t get64(const uint8_t* p) { return get32(p) | (static_cast<uint64_t>(get32(p + 4)) << 32); }
bool zeroes(const uint8_t* p, size_t n) {
  for (size_t i = 0; i < n; ++i) if (p[i] != 0) return false;
  return true;
}

bool validUtf8Name(const uint8_t* bytes, uint8_t length) {
  if (!bytes || length == 0 || length > kMaxNameBytes) return false;
  size_t i = 0;
  while (i < length) {
    const uint8_t c = bytes[i];
    if (c == 0 || c < 0x20 || c == 0x7F || c == '/' || c == '\\') return false;
    if (c < 0x80) { ++i; continue; }
    uint32_t cp = 0;
    uint8_t continuation = 0;
    if (c >= 0xC2 && c <= 0xDF) { cp = c & 0x1F; continuation = 1; }
    else if (c >= 0xE0 && c <= 0xEF) { cp = c & 0x0F; continuation = 2; }
    else if (c >= 0xF0 && c <= 0xF4) { cp = c & 0x07; continuation = 3; }
    else return false;
    if (i + continuation >= length) return false;
    for (uint8_t j = 1; j <= continuation; ++j) {
      const uint8_t next = bytes[i + j];
      if ((next & 0xC0) != 0x80) return false;
      cp = (cp << 6) | (next & 0x3F);
    }
    if ((continuation == 2 && cp < 0x800) || (continuation == 3 && cp < 0x10000) ||
        (cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF || (cp >= 0x7F && cp <= 0x9F)) return false;
    i += static_cast<size_t>(continuation) + 1;
  }
  return true;
}

bool validRoot(const RootIndex& v) {
  if (v.bank > 1 || v.entryCount > kMaxCollections || v.totalPublishedPages > kMaxPublishedPages) return false;
  if (v.entryCount == 0) return v.selectedCollectionId == 0 && v.totalPublishedPages == 0;
  if (!v.generation || !v.totalPublishedPages) return false;
  bool selectedFound = false;
  for (uint8_t i = 0; i < v.entryCount; ++i) {
    const RootEntry& e = v.entries[i];
    if (!e.collectionId || !e.expectedGeneration || (e.flags & ~(kEnabled | kPinned)) ||
        !validUtf8Name(e.name, e.nameLength)) return false;
    if (e.collectionId == v.selectedCollectionId && (e.flags & kEnabled)) selectedFound = true;
    for (uint8_t j = 0; j < i; ++j) {
      if (v.entries[j].collectionId == e.collectionId ||
          ((v.entries[j].flags & kEnabled) && (e.flags & kEnabled) && v.entries[j].order == e.order)) return false;
    }
  }
  return selectedFound;
}

bool validManifest(const CollectionManifest& v) {
  if (v.bank > 1 || !v.collectionId || !v.generation || !v.width || !v.height ||
      v.pageCount == 0 || v.pageCount > kMaxPagesPerCollection || v.activePage >= v.pageCount) return false;
  for (uint8_t i = v.pageCount; i < kMaxPagesPerCollection; ++i) if (v.pageCrc[i]) return false;
  return true;
}

bool validSelection(const Selection& v) {
  return static_cast<uint8_t>(v.rotationMode) <= static_cast<uint8_t>(RotationMode::Manual) &&
         v.rootGeneration && v.collectionId && v.collectionGeneration && v.pageIndex < kMaxPagesPerCollection;
}

bool validSession(const SessionJournal& v) {
  if (v.journalBank > 1 || v.candidateBank > 1 || !v.journalSequence || !v.sessionId || !v.collectionId ||
      v.resultingPageCount == 0 || v.resultingPageCount > kMaxPagesPerCollection ||
      static_cast<uint8_t>(v.state) > static_cast<uint8_t>(SessionState::Ready) ||
      static_cast<uint8_t>(v.mode) > static_cast<uint8_t>(SessionMode::PartialReplace)) return false;
  const uint16_t all = v.resultingPageCount == 16 ? 0xFFFFu : static_cast<uint16_t>((1u << v.resultingPageCount) - 1u);
  if (!v.requiredPageBitmap || (v.requiredPageBitmap & ~all) ||
      (v.receivedPageBitmap & ~v.requiredPageBitmap)) return false;
  if (v.state == SessionState::Ready && v.receivedPageBitmap != v.requiredPageBitmap) return false;
  if (v.mode == SessionMode::Create) {
    if (v.baseCollectionGeneration != 0 || v.candidateCollectionGeneration != 1 || v.requiredPageBitmap != all) return false;
  } else {
    if (!v.baseCollectionGeneration || v.candidateCollectionGeneration != v.baseCollectionGeneration + 1u ||
        !v.candidateCollectionGeneration) return false;
    if (v.mode == SessionMode::FullReplace && v.requiredPageBitmap != all) return false;
    if (v.mode == SessionMode::PartialReplace && v.requiredPageBitmap == all) return false;
  }
  for (uint8_t i = v.resultingPageCount; i < kMaxPagesPerCollection; ++i) if (v.pageCrc[i]) return false;
  return true;
}

bool headerAndCrc(const uint8_t* input, size_t length, const uint8_t magic[4]) {
  return input && length >= 8 && !std::memcmp(input, magic, 4) && input[4] == kVersion &&
         crc32(input, length - 4) == get32(input + length - 4);
}

}  // namespace

uint32_t crc32(const uint8_t* data, size_t length) {
  if (!data && length) return 0;
  uint32_t crc = 0xFFFFFFFFu;
  for (size_t i = 0; i < length; ++i) {
    crc ^= data[i];
    for (uint8_t bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
  }
  return crc ^ 0xFFFFFFFFu;
}

bool generationIsNewerOrEqual(uint32_t candidate, uint32_t reference) {
  return static_cast<int32_t>(candidate - reference) >= 0;
}

size_t encodedRootSize(const RootIndex& value) {
  return value.entryCount <= kMaxCollections ? kRootHeaderSize + kRootEntrySize * value.entryCount + 4 : 0;
}

bool encodeRoot(const RootIndex& value, uint8_t* output, size_t capacity, size_t& written) {
  written = 0;
  const size_t size = encodedRootSize(value);
  if (!output || !size || capacity < size || !validRoot(value)) return false;
  std::memset(output, 0, size);
  std::memcpy(output, kRootMagic, 4); output[4] = kVersion; output[5] = value.bank; output[6] = value.entryCount;
  put32(output + 8, value.generation); put32(output + 12, value.selectedCollectionId);
  put16(output + 16, value.totalPublishedPages);
  for (uint8_t i = 0; i < value.entryCount; ++i) {
    const RootEntry& e = value.entries[i]; uint8_t* p = output + kRootHeaderSize + i * kRootEntrySize;
    put32(p, e.collectionId); p[4] = e.flags; p[5] = e.order; p[6] = e.nameLength;
    std::memcpy(p + 7, e.name, e.nameLength); put32(p + 31, e.expectedGeneration);
  }
  put32(output + size - 4, crc32(output, size - 4)); written = size; return true;
}

bool decodeRoot(const uint8_t* input, size_t length, RootIndex& value) {
  if (!input || length < kRootHeaderSize + 4 || input[6] > kMaxCollections ||
      length != kRootHeaderSize + kRootEntrySize * input[6] + 4 || !headerAndCrc(input, length, kRootMagic) ||
      input[7] || !zeroes(input + 18, 6)) return false;
  RootIndex decoded{}; decoded.bank = input[5]; decoded.entryCount = input[6]; decoded.generation = get32(input + 8);
  decoded.selectedCollectionId = get32(input + 12); decoded.totalPublishedPages = get16(input + 16);
  for (uint8_t i = 0; i < decoded.entryCount; ++i) {
    const uint8_t* p = input + kRootHeaderSize + i * kRootEntrySize; RootEntry& e = decoded.entries[i];
    e.collectionId = get32(p); e.flags = p[4]; e.order = p[5]; e.nameLength = p[6];
    if (e.nameLength > kMaxNameBytes || !zeroes(p + 7 + e.nameLength, kMaxNameBytes - e.nameLength)) return false;
    std::memcpy(e.name, p + 7, kMaxNameBytes); e.expectedGeneration = get32(p + 31);
  }
  if (!validRoot(decoded)) return false; value = decoded; return true;
}

bool encodeManifest(const CollectionManifest& v, uint8_t* out, size_t cap) {
  if (!out || cap < kManifestSize || !validManifest(v)) return false;
  std::memset(out, 0, kManifestSize); std::memcpy(out, kManifestMagic, 4); out[4] = kVersion;
  out[5] = v.bank; out[6] = v.pageCount; out[7] = v.activePage; put16(out + 8, v.width); put16(out + 10, v.height);
  put32(out + 12, v.collectionId); put32(out + 16, v.generation);
  for (uint8_t i = 0; i < kMaxPagesPerCollection; ++i) put32(out + 24 + i * 4, v.pageCrc[i]);
  put32(out + 88, crc32(out, 88)); return true;
}

bool decodeManifest(const uint8_t* in, size_t length, CollectionManifest& value) {
  if (length != kManifestSize || !headerAndCrc(in, length, kManifestMagic) || !zeroes(in + 20, 4)) return false;
  CollectionManifest v{}; v.bank = in[5]; v.pageCount = in[6]; v.activePage = in[7]; v.width = get16(in + 8);
  v.height = get16(in + 10); v.collectionId = get32(in + 12); v.generation = get32(in + 16);
  for (uint8_t i = 0; i < kMaxPagesPerCollection; ++i) v.pageCrc[i] = get32(in + 24 + i * 4);
  if (!validManifest(v)) return false; value = v; return true;
}

bool encodeSelection(const Selection& v, uint8_t* out, size_t cap) {
  if (!out || cap < kSelectionSize || !validSelection(v)) return false;
  std::memset(out, 0, kSelectionSize); std::memcpy(out, kSelectionMagic, 4); out[4] = kVersion;
  out[5] = static_cast<uint8_t>(v.rotationMode); out[6] = v.pageIndex; put32(out + 8, v.rootGeneration);
  put32(out + 12, v.collectionId); put32(out + 16, v.collectionGeneration); put32(out + 20, crc32(out, 20)); return true;
}

bool decodeSelection(const uint8_t* in, size_t length, Selection& value) {
  if (length != kSelectionSize || !headerAndCrc(in, length, kSelectionMagic) || in[7]) return false;
  Selection v{}; v.rotationMode = static_cast<RotationMode>(in[5]); v.pageIndex = in[6];
  v.rootGeneration = get32(in + 8); v.collectionId = get32(in + 12); v.collectionGeneration = get32(in + 16);
  if (!validSelection(v)) return false; value = v; return true;
}

bool encodeSession(const SessionJournal& v, uint8_t* out, size_t cap) {
  if (!out || cap < kSessionSize || !validSession(v)) return false;
  std::memset(out, 0, kSessionSize); std::memcpy(out, kSessionMagic, 4); out[4] = kVersion; out[5] = v.journalBank;
  out[6] = static_cast<uint8_t>(v.state); out[7] = static_cast<uint8_t>(v.mode); put32(out + 8, v.journalSequence);
  put64(out + 12, v.sessionId); put32(out + 20, v.collectionId); put32(out + 24, v.baseRootGeneration);
  put32(out + 28, v.baseCollectionGeneration); put32(out + 32, v.candidateCollectionGeneration);
  out[36] = v.resultingPageCount; out[37] = v.candidateBank; put16(out + 40, v.requiredPageBitmap);
  put16(out + 42, v.receivedPageBitmap);
  for (uint8_t i = 0; i < kMaxPagesPerCollection; ++i) put32(out + 44 + i * 4, v.pageCrc[i]);
  put32(out + 108, crc32(out, 108)); return true;
}

bool decodeSession(const uint8_t* in, size_t length, SessionJournal& value) {
  if (length != kSessionSize || !headerAndCrc(in, length, kSessionMagic) || !zeroes(in + 38, 2)) return false;
  SessionJournal v{}; v.journalBank = in[5]; v.state = static_cast<SessionState>(in[6]);
  v.mode = static_cast<SessionMode>(in[7]); v.journalSequence = get32(in + 8); v.sessionId = get64(in + 12);
  v.collectionId = get32(in + 20); v.baseRootGeneration = get32(in + 24);
  v.baseCollectionGeneration = get32(in + 28); v.candidateCollectionGeneration = get32(in + 32);
  v.resultingPageCount = in[36]; v.candidateBank = in[37]; v.requiredPageBitmap = get16(in + 40);
  v.receivedPageBitmap = get16(in + 42);
  for (uint8_t i = 0; i < kMaxPagesPerCollection; ++i) v.pageCrc[i] = get32(in + 44 + i * 4);
  if (!validSession(v)) return false; value = v; return true;
}

bool encodeInventory(const RootIndex* root, const CollectionManifest* manifests,
                     const Selection* selection, const SessionJournal* session,
                     uint8_t* out, size_t cap, size_t& written) {
  written = 0;
  const uint8_t count = root ? root->entryCount : 0;
  const size_t size = kInventoryHeaderSize + kInventoryEntrySize * count + 4;
  if (!out || cap < size || count > kMaxCollections || (root && (!manifests || !validRoot(*root))) ||
      (root && count == 0 && root->generation != 0)) return false;

  std::memset(out, 0, size);
  std::memcpy(out, kInventoryMagic, sizeof(kInventoryMagic));
  out[4] = kVersion;
  out[5] = count;
  out[6] = session ? 1u : 0u;
  out[44] = session ? static_cast<uint8_t>(session->state) : 0xFFu;
  out[45] = session ? static_cast<uint8_t>(session->mode) : 0xFFu;

  if (root) {
    put32(out + 8, root->generation);
    put32(out + 12, root->selectedCollectionId);
    put16(out + 21, root->totalPublishedPages);
    uint16_t pageSum = 0;
    bool selectedFound = false;
    bool usedOrder[256] = {};
    for (uint8_t i = 0; i < count; ++i) {
      const RootEntry& entry = root->entries[i];
      const CollectionManifest& manifest = manifests[i];
      if (!validManifest(manifest) || manifest.collectionId != entry.collectionId ||
          manifest.generation != entry.expectedGeneration || usedOrder[entry.order]) return false;
      usedOrder[entry.order] = true;
      if (pageSum > kMaxPublishedPages - manifest.pageCount) return false;
      pageSum = static_cast<uint16_t>(pageSum + manifest.pageCount);
      uint8_t* p = out + kInventoryHeaderSize + i * kInventoryEntrySize;
      put32(p, entry.collectionId); put32(p + 4, entry.expectedGeneration);
      p[8] = manifest.pageCount; p[9] = entry.flags; p[10] = entry.order;
      if (entry.collectionId == root->selectedCollectionId) {
        if (!(entry.flags & kEnabled)) return false;
        put32(out + 16, entry.expectedGeneration);
        uint8_t selectedPage = manifest.activePage;
        if (selection && selection->rootGeneration == root->generation &&
            selection->collectionId == entry.collectionId &&
            selection->collectionGeneration == entry.expectedGeneration &&
            selection->pageIndex < manifest.pageCount) selectedPage = selection->pageIndex;
        out[20] = selectedPage;
        selectedFound = true;
      }
    }
    if (!selectedFound || pageSum != root->totalPublishedPages) return false;
  }

  if (session) {
    // Re-encode as the single source of truth for SCT5 invariants without exposing its bytes.
    uint8_t checked[kSessionSize];
    if (!encodeSession(*session, checked, sizeof(checked))) return false;
    put64(out + 24, session->sessionId); put32(out + 32, session->collectionId);
    put32(out + 36, session->candidateCollectionGeneration);
    put16(out + 40, session->requiredPageBitmap); put16(out + 42, session->receivedPageBitmap);
    put32(out + 48, session->journalSequence);
  }

  put32(out + size - 4, crc32(out, size - 4));
  written = size;
  return true;
}

}  // namespace sidecar::v5
