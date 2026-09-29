#include <gtest/gtest.h>

#include <array>
#include <cstring>

#include "SidecarCollectionsV5.h"
#include "SidecarCollectionsV5Store.h"

using namespace sidecar::v5;

namespace {
void refreshCrc(uint8_t* bytes, size_t length) {
  const uint32_t value = crc32(bytes, length - 4);
  bytes[length - 4] = static_cast<uint8_t>(value);
  bytes[length - 3] = static_cast<uint8_t>(value >> 8);
  bytes[length - 2] = static_cast<uint8_t>(value >> 16);
  bytes[length - 1] = static_cast<uint8_t>(value >> 24);
}

RootIndex rootFixture() {
  RootIndex r{}; r.bank = 1; r.entryCount = 2; r.generation = 9; r.selectedCollectionId = 0x10203040;
  r.totalPublishedPages = 5;
  r.entries[0].collectionId = 0x10203040; r.entries[0].flags = kEnabled; r.entries[0].order = 2;
  r.entries[0].nameLength = 7; std::memcpy(r.entries[0].name, "Weather", 7); r.entries[0].expectedGeneration = 11;
  r.entries[1].collectionId = 0x50607080; r.entries[1].flags = kEnabled | kPinned; r.entries[1].order = 4;
  const uint8_t name[] = {'C', 'a', 'f', 0xC3, 0xA9}; r.entries[1].nameLength = sizeof(name);
  std::memcpy(r.entries[1].name, name, sizeof(name)); r.entries[1].expectedGeneration = 3;
  return r;
}

CollectionManifest manifestFixture() {
  CollectionManifest m{}; m.bank = 1; m.pageCount = 3; m.activePage = 1; m.width = 792; m.height = 528;
  m.collectionId = 0x10203040; m.generation = 11;
  m.pageCrc[0] = 0x11111111; m.pageCrc[1] = 0x22222222; m.pageCrc[2] = 0x33333333; return m;
}

SessionJournal sessionFixture() {
  SessionJournal s{}; s.journalBank = 1; s.state = SessionState::Receiving; s.mode = SessionMode::PartialReplace;
  s.journalSequence = 8; s.sessionId = 0x0123456789ABCDEFull; s.collectionId = 0x10203040;
  s.baseRootGeneration = 9; s.baseCollectionGeneration = 11; s.candidateCollectionGeneration = 12;
  s.resultingPageCount = 3; s.candidateBank = 0; s.requiredPageBitmap = 0b010; s.receivedPageBitmap = 0;
  s.pageCrc[0] = 0x11111111; s.pageCrc[1] = 0xAAAAAAAA; s.pageCrc[2] = 0x33333333; return s;
}
}  // namespace

TEST(SidecarCollectionsV5, CrcGoldenVectorAndGenerationWrap) {
  const uint8_t text[] = {'1','2','3','4','5','6','7','8','9'};
  EXPECT_EQ(crc32(text, sizeof(text)), 0xCBF43926u);
  EXPECT_TRUE(generationIsNewerOrEqual(1, 0xFFFFFFFFu));
  EXPECT_FALSE(generationIsNewerOrEqual(0xFFFFFFFFu, 1));
}

TEST(SidecarCollectionsV5, RootRoundTripHasExactSizeAndLittleEndianHeader) {
  RootIndex source = rootFixture(), decoded{}; std::array<uint8_t, kRootMaxSize> bytes{}; size_t written = 0;
  ASSERT_TRUE(encodeRoot(source, bytes.data(), bytes.size(), written));
  EXPECT_EQ(written, kRootHeaderSize + 2 * kRootEntrySize + 4); EXPECT_EQ(bytes[5], 1); EXPECT_EQ(bytes[8], 9);
  ASSERT_TRUE(decodeRoot(bytes.data(), written, decoded));
  EXPECT_EQ(decoded.entries[1].collectionId, source.entries[1].collectionId);
  EXPECT_EQ(decoded.entries[1].nameLength, 5); EXPECT_EQ(decoded.totalPublishedPages, 5);
}

TEST(SidecarCollectionsV5, RootRejectsCorruptionReservedBytesDuplicatesAndInvalidNames) {
  RootIndex r = rootFixture(); std::array<uint8_t, kRootMaxSize> bytes{}; size_t written = 0; RootIndex decoded{};
  ASSERT_TRUE(encodeRoot(r, bytes.data(), bytes.size(), written)); bytes[10] ^= 1;
  EXPECT_FALSE(decodeRoot(bytes.data(), written, decoded));
  r.entries[1].collectionId = r.entries[0].collectionId; EXPECT_FALSE(encodeRoot(r, bytes.data(), bytes.size(), written));
  r = rootFixture(); r.entries[0].name[0] = '/'; EXPECT_FALSE(encodeRoot(r, bytes.data(), bytes.size(), written));
  r = rootFixture(); r.entries[0].nameLength = 2; r.entries[0].name[0] = 0xC0; r.entries[0].name[1] = 0x80;
  EXPECT_FALSE(encodeRoot(r, bytes.data(), bytes.size(), written));
}

TEST(SidecarCollectionsV5, ManifestRoundTripAndStrictUnusedCrcs) {
  CollectionManifest m = manifestFixture(), decoded{}; std::array<uint8_t, kManifestSize> bytes{};
  ASSERT_TRUE(encodeManifest(m, bytes.data(), bytes.size())); ASSERT_TRUE(decodeManifest(bytes.data(), bytes.size(), decoded));
  EXPECT_EQ(decoded.width, 792); EXPECT_EQ(decoded.pageCrc[2], 0x33333333u);
  m.pageCrc[7] = 1; EXPECT_FALSE(encodeManifest(m, bytes.data(), bytes.size()));
  m = manifestFixture(); m.activePage = 3; EXPECT_FALSE(encodeManifest(m, bytes.data(), bytes.size()));
}

TEST(SidecarCollectionsV5, SelectionRoundTripAndReservedByteValidation) {
  Selection s{}; s.rotationMode = RotationMode::Manual; s.pageIndex = 2; s.rootGeneration = 9;
  s.collectionId = 0x10203040; s.collectionGeneration = 11;
  std::array<uint8_t, kSelectionSize> bytes{}; Selection decoded{};
  ASSERT_TRUE(encodeSelection(s, bytes.data(), bytes.size())); ASSERT_TRUE(decodeSelection(bytes.data(), bytes.size(), decoded));
  EXPECT_EQ(decoded.pageIndex, 2); bytes[7] = 1; refreshCrc(bytes.data(), bytes.size());
  EXPECT_FALSE(decodeSelection(bytes.data(), bytes.size(), decoded));
}

TEST(SidecarCollectionsV5, SessionRoundTripAndStateInvariants) {
  SessionJournal s = sessionFixture(), decoded{}; std::array<uint8_t, kSessionSize> bytes{};
  ASSERT_TRUE(encodeSession(s, bytes.data(), bytes.size())); ASSERT_TRUE(decodeSession(bytes.data(), bytes.size(), decoded));
  EXPECT_EQ(decoded.sessionId, s.sessionId); EXPECT_EQ(decoded.requiredPageBitmap, 0b010);
  s.state = SessionState::Ready; EXPECT_FALSE(encodeSession(s, bytes.data(), bytes.size()));
  s.receivedPageBitmap = s.requiredPageBitmap; EXPECT_TRUE(encodeSession(s, bytes.data(), bytes.size()));
  s = sessionFixture(); s.candidateCollectionGeneration = 13; EXPECT_FALSE(encodeSession(s, bytes.data(), bytes.size()));
  s = sessionFixture(); s.requiredPageBitmap = 0b111; EXPECT_FALSE(encodeSession(s, bytes.data(), bytes.size()));
}

TEST(SidecarCollectionsV5, CreateSessionRequiresEveryPage) {
  SessionJournal s = sessionFixture(); s.mode = SessionMode::Create; s.baseCollectionGeneration = 0;
  s.candidateCollectionGeneration = 1; s.requiredPageBitmap = 0b111;
  std::array<uint8_t, kSessionSize> bytes{}; EXPECT_TRUE(encodeSession(s, bytes.data(), bytes.size()));
  s.requiredPageBitmap = 0b011; EXPECT_FALSE(encodeSession(s, bytes.data(), bytes.size()));
}

TEST(SidecarCollectionsV5, CapacityAdmissionUsesMeasuredReserve) {
  CapacityAdmission result{};
  ASSERT_TRUE(evaluateCapacity(31247564800ull, 2293760ull, 64ull * 52272ull, result));
  EXPECT_EQ(result.reserve, 1562378240ull); EXPECT_TRUE(result.admitted);
  EXPECT_FALSE(evaluateCapacity(1000, 0, 1, result));
  ASSERT_TRUE(evaluateCapacity(20ull * 1024 * 1024, 12ull * 1024 * 1024, 1, result));
  EXPECT_FALSE(result.admitted);
}

TEST(SidecarCollectionsV5, FixedPathsAreBoundedAndCanonical) {
  char path[80];
  ASSERT_TRUE(Store::rootPath(1, path, sizeof(path))); EXPECT_STREQ(path, "/.crosspoint/sidecar_v5/root_1.index");
  EXPECT_FALSE(Store::rootPath(2, path, sizeof(path)));
  ASSERT_TRUE(Store::manifestPath(0xABCD, 0, path, sizeof(path)));
  EXPECT_STREQ(path, "/.crosspoint/sidecar_v5/c_0000ABCD_0.manifest");
  ASSERT_TRUE(Store::pagePath(0x10, 1, 15, path, sizeof(path)));
  EXPECT_STREQ(path, "/.crosspoint/sidecar_v5/c_00000010_1_15.bin");
  EXPECT_FALSE(Store::pagePath(0x10, 1, 16, path, sizeof(path)));
}

TEST(SidecarCollectionsV5, EmptyInventoryHasExactSentinelsAndCrc) {
  std::array<uint8_t, kInventoryMaxSize> bytes{}; size_t written = 0;
  ASSERT_TRUE(encodeInventory(nullptr, nullptr, nullptr, nullptr, bytes.data(), bytes.size(), written));
  ASSERT_EQ(written, 56u); EXPECT_EQ(std::memcmp(bytes.data(), "SIV5", 4), 0);
  EXPECT_EQ(bytes[4], 1); EXPECT_EQ(bytes[5], 0); EXPECT_EQ(bytes[6], 0);
  EXPECT_EQ(bytes[44], 0xFF); EXPECT_EQ(bytes[45], 0xFF);
  uint32_t storedCrc = bytes[52] | (static_cast<uint32_t>(bytes[53]) << 8) |
                       (static_cast<uint32_t>(bytes[54]) << 16) |
                       (static_cast<uint32_t>(bytes[55]) << 24);
  EXPECT_EQ(storedCrc, crc32(bytes.data(), 52));
}

TEST(SidecarCollectionsV5, InventoryEncodesRootSelectionEntriesAndSession) {
  RootIndex root = rootFixture();
  CollectionManifest manifests[2] = {manifestFixture(), manifestFixture()};
  manifests[1].collectionId = root.entries[1].collectionId; manifests[1].generation = 3;
  manifests[1].pageCount = 2; manifests[1].activePage = 0; manifests[1].pageCrc[2] = 0;
  Selection selection{}; selection.rootGeneration = 9; selection.collectionId = root.selectedCollectionId;
  selection.collectionGeneration = 11; selection.pageIndex = 2;
  SessionJournal session = sessionFixture();
  std::array<uint8_t, kInventoryMaxSize> bytes{}; size_t written = 0;
  ASSERT_TRUE(encodeInventory(&root, manifests, &selection, &session, bytes.data(), bytes.size(), written));
  ASSERT_EQ(written, 80u); EXPECT_EQ(bytes[5], 2); EXPECT_EQ(bytes[6], 1); EXPECT_EQ(bytes[20], 2);
  EXPECT_EQ(bytes[21], 5); EXPECT_EQ(bytes[44], 0); EXPECT_EQ(bytes[45], 2); EXPECT_EQ(bytes[48], 8);
  EXPECT_EQ(bytes[52], 0x40); EXPECT_EQ(bytes[60], 3); EXPECT_EQ(bytes[61], kEnabled);
  EXPECT_EQ(bytes[64], 0x80); EXPECT_EQ(bytes[72], 2); EXPECT_EQ(bytes[73], kEnabled | kPinned);
}

TEST(SidecarCollectionsV5, InventoryRejectsInconsistentPublishedState) {
  RootIndex root = rootFixture(); CollectionManifest manifests[2] = {manifestFixture(), manifestFixture()};
  manifests[1].collectionId = root.entries[1].collectionId; manifests[1].generation = 3;
  manifests[1].pageCount = 2; manifests[1].activePage = 0; manifests[1].pageCrc[2] = 0;
  std::array<uint8_t, kInventoryMaxSize> bytes{}; size_t written = 0;
  manifests[1].generation = 4;
  EXPECT_FALSE(encodeInventory(&root, manifests, nullptr, nullptr, bytes.data(), bytes.size(), written));
  manifests[1].generation = 3; root.entries[1].order = root.entries[0].order;
  EXPECT_FALSE(encodeInventory(&root, manifests, nullptr, nullptr, bytes.data(), bytes.size(), written));
  root = {}; root.bank = 0; root.generation = 1;
  EXPECT_FALSE(encodeInventory(&root, manifests, nullptr, nullptr, bytes.data(), bytes.size(), written));
}
