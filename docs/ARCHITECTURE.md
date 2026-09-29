# Architecture

The phone owns data collection and page rendering. It converts each 528 × 792 monochrome page into a
packed 1-bpp frame and chooses raw, bounded PNG, or raw-DEFLATE encoding based on firmware capability
advertisement and encoded size.

Each pane is a collection with its own identifier, generation, ordered pages and refresh class. The
phone asks the X3 for its durable inventory, sends only missing pages, and can resume a partially
received session later.

The X3 writes incoming bytes to an unpublished candidate page, decodes them through bounded buffers,
checks the raw CRC, reads the persisted file back, and checks that CRC as well. Session metadata tracks
which pages are durable. Only a complete collection is atomically published into the active root.

Published collections are independent. Updating weather does not require resending astronomy, notes,
markets or recipes. During transfer, the display continues paging the previous published root. After
commit, the new generation enters the local rotation.

Recipes use a fixed-display policy. Other enabled collections participate in the local timed rotation.
The phone controls how often online data is refreshed; the X3 controls page-to-page display timing.
