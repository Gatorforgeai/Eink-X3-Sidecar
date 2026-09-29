#pragma once

#include <cstddef>
#include <cstdint>

namespace sidecar::v5 {

constexpr uint8_t kVersion = 1;
constexpr uint8_t kMaxCollections = 16;
constexpr uint8_t kMaxPagesPerCollection = 16;
constexpr uint16_t kMaxPublishedPages = 64;
constexpr uint8_t kMaxNameBytes = 24;

constexpr size_t kRootHeaderSize = 24;
constexpr size_t kRootEntrySize = 35;
constexpr size_t kRootMaxSize = kRootHeaderSize + kRootEntrySize * kMaxCollections + 4;
constexpr size_t kManifestSize = 92;
constexpr size_t kSelectionSize = 24;
constexpr size_t kSessionSize = 112;
constexpr size_t kInventoryHeaderSize = 52;
constexpr size_t kInventoryEntrySize = 12;
constexpr size_t kInventoryMaxSize = kInventoryHeaderSize + kInventoryEntrySize * kMaxCollections + 4;

enum CollectionFlags : uint8_t { kEnabled = 1u << 0, kPinned = 1u << 1 };
enum class RotationMode : uint8_t { Automatic = 0, Manual = 1 };
enum class SessionMode : uint8_t { Create = 0, FullReplace = 1, PartialReplace = 2 };
enum class SessionState : uint8_t { Receiving = 0, Ready = 1 };

struct RootEntry {
  uint32_t collectionId = 0;
  uint8_t flags = 0;
  uint8_t order = 0;
  uint8_t nameLength = 0;
  uint8_t name[kMaxNameBytes] = {};
  uint32_t expectedGeneration = 0;
};

struct RootIndex {
  uint8_t bank = 0;
  uint8_t entryCount = 0;
  uint32_t generation = 0;
  uint32_t selectedCollectionId = 0;
  uint16_t totalPublishedPages = 0;
  RootEntry entries[kMaxCollections] = {};
};

struct CollectionManifest {
  uint8_t bank = 0;
  uint8_t pageCount = 0;
  uint8_t activePage = 0;
  uint16_t width = 0;
  uint16_t height = 0;
  uint32_t collectionId = 0;
  uint32_t generation = 0;
  uint32_t pageCrc[kMaxPagesPerCollection] = {};
};

struct Selection {
  RotationMode rotationMode = RotationMode::Automatic;
  uint8_t pageIndex = 0;
  uint32_t rootGeneration = 0;
  uint32_t collectionId = 0;
  uint32_t collectionGeneration = 0;
};

struct SessionJournal {
  uint8_t journalBank = 0;
  SessionState state = SessionState::Receiving;
  SessionMode mode = SessionMode::Create;
  uint32_t journalSequence = 0;
  uint64_t sessionId = 0;
  uint32_t collectionId = 0;
  uint32_t baseRootGeneration = 0;
  uint32_t baseCollectionGeneration = 0;
  uint32_t candidateCollectionGeneration = 0;
  uint8_t resultingPageCount = 0;
  uint8_t candidateBank = 0;
  uint16_t requiredPageBitmap = 0;
  uint16_t receivedPageBitmap = 0;
  uint32_t pageCrc[kMaxPagesPerCollection] = {};
};

uint32_t crc32(const uint8_t* data, size_t length);
bool generationIsNewerOrEqual(uint32_t candidate, uint32_t reference);

size_t encodedRootSize(const RootIndex& value);
bool encodeRoot(const RootIndex& value, uint8_t* output, size_t capacity, size_t& written);
bool decodeRoot(const uint8_t* input, size_t length, RootIndex& value);

bool encodeManifest(const CollectionManifest& value, uint8_t* output, size_t capacity);
bool decodeManifest(const uint8_t* input, size_t length, CollectionManifest& value);

bool encodeSelection(const Selection& value, uint8_t* output, size_t capacity);
bool decodeSelection(const uint8_t* input, size_t length, Selection& value);

bool encodeSession(const SessionJournal& value, uint8_t* output, size_t capacity);
bool decodeSession(const uint8_t* input, size_t length, SessionJournal& value);

// Encodes the exact SIV5 inventory value described by protocol v5. The root pointer may be null
// for the canonical empty-root snapshot. Manifests must be in root order when a root is present.
// Selection may be null; in that case each manifest's durable active page supplies the selected
// page for the root's selected collection. Session may be null for the absent-session sentinels.
bool encodeInventory(const RootIndex* root, const CollectionManifest* manifests,
                     const Selection* selection, const SessionJournal* session,
                     uint8_t* output, size_t capacity, size_t& written);

}  // namespace sidecar::v5
