add_executable(SidecarCollectionsV5Test
  SidecarCollectionsV5Test.cpp
  ${REPO_ROOT}/src/SidecarCollectionsV5.cpp
  ${REPO_ROOT}/src/SidecarCollectionsV5StoreFormat.cpp
)

target_include_directories(SidecarCollectionsV5Test PRIVATE ${REPO_ROOT}/src)
target_link_libraries(SidecarCollectionsV5Test PRIVATE crosspoint_test_common GTest::gtest_main)
gtest_discover_tests(SidecarCollectionsV5Test)
