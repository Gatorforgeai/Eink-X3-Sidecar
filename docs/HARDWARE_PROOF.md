# Hardware proof — 2026-09-28

Phone: Samsung SM-S938U1  
X3: ESP32-C3

## Two-page batch

Phone log:

```text
v5 inventory root=15 collections=6 pages=9 session=true
v5 page=0 encoding=DEFLATE_RAW bytes=6564 raw=52272
v5 page=1 encoding=DEFLATE_RAW bytes=8331 raw=52272
v5 canary collection=2 ... received=0x0 sending=0, 1
v5 inventory root=16 collections=6 pages=9 session=false
v5 canary published collection=2 generation=3
```

X3 serial:

```text
page 0 durable encoding=2 bytes=6564 bitmap=0001
page 1 durable encoding=2 bytes=8331 bitmap=0003
v5 commit restored screen ownership
```

## Four-page batch

Phone log:

```text
v5 inventory root=16 collections=6 pages=9 session=true
v5 page=0 encoding=DEFLATE_RAW bytes=5828 raw=52272
v5 page=1 encoding=DEFLATE_RAW bytes=5615 raw=52272
v5 page=2 encoding=DEFLATE_RAW bytes=5420 raw=52272
v5 page=3 encoding=DEFLATE_RAW bytes=5313 raw=52272
v5 canary collection=10 ... received=0x0 sending=0, 1, 2, 3
v5 inventory root=17 collections=7 pages=13 session=false
v5 canary published collection=10 generation=1
```

X3 serial:

```text
page 0 durable encoding=2 bytes=5828 bitmap=0001
page 1 durable encoding=2 bytes=5615 bitmap=0003
page 2 durable encoding=2 bytes=5420 bitmap=0007
page 3 durable encoding=2 bytes=5313 bitmap=000f
v5 page shown collection=4 generation=4 page=1/2 ordinal=1/12
v5 commit restored screen ownership
```

The published rotation continued while pages were staged. The four new pages appeared only after the
commit, increasing the visible sequence from 8 to 12 pages.

## Full automatic rotation and selective retry

The normal Start Enabled Rotation path populated 11 independent collections and 24 enabled pages.
Weather, sports headlines, football, fantasy leaders, email, stocks, currencies and the other enabled
modules updated one collection at a time while the X3 continued paging the published catalog.

The final six-page Recent Email replacement exercised delayed session creation and recovery:

```text
v5 inventory root=24 collections=11 pages=25 session=true
v5 page=0 encoding=DEFLATE_RAW bytes=10144 raw=52272
v5 page=1 encoding=DEFLATE_RAW bytes=8961 raw=52272
v5 page=2 encoding=DEFLATE_RAW bytes=7350 raw=52272
v5 page=3 encoding=DEFLATE_RAW bytes=10064 raw=52272
v5 canary ... received=0x0 sending=0, 1, 2, 3
v5 page=4 encoding=DEFLATE_RAW bytes=8187 raw=52272
v5 page=5 encoding=DEFLATE_RAW bytes=4443 raw=52272
v5 canary ... received=0xf sending=4, 5
v5 canary ... received=0x2f sending=4
v5 canary ... received=0x3f sending=
v5 inventory root=25 collections=11 pages=25 session=false
v5 canary published collection=11 generation=2
```

Page 4 failed its first integrity pass. The firmware retained the other five verified pages and the
phone retransmitted only page 4. The old six-page email collection remained visible until the complete
replacement was committed atomically.
