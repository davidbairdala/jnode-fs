# btrfs (read-only)

A read-only btrfs reader for jnode-fs: enough to enumerate the directory tree, report file
sizes, and read file contents. Built for disk-usage / forensic browsing of btrfs volumes inside
VM disk images (the motivating use case: SpaceMap could not read Fedora/openSUSE roots, which
default to btrfs — jnode-fs previously had no btrfs support).

## Scope

**Supported**
- Single-device volumes (VM images are single-device). Multi-device / RAID is rejected cleanly.
- Superblock → `sys_chunk_array` bootstrap → chunk tree → logical→physical address map.
- Root tree → FS tree(s); descent into subvolumes (each is its own FS tree).
- FS-tree walk: `INODE_ITEM` (size, mode), `DIR_INDEX` (directory entries).
- File content: inline extents, regular extents, and zlib-compressed extents.
- crc32c is the checksum; verification is optional (skipped for read-only browsing).

**Not supported (degrades, does not crash)**
- lzo / zstd compressed file *contents* — the file still appears with its correct size (size
  comes from the inode); reading such a file's bytes throws a clear "compression not supported".
- Writing (read-only), multi-device/RAID, and the extent/csum/free-space trees (not needed to
  walk the FS tree).

## Why this is the right layer

btrfs stores everything in copy-on-write B-trees keyed by `(objectid, type, offset)`. For a
read-only tree walk we only need three trees:
1. **chunk tree** — translate btrfs *logical* addresses to *physical* device offsets
   (bootstrapped by the superblock's `sys_chunk_array` so the chunk tree itself is readable);
2. **root tree** — locate the FS tree of each subvolume (`ROOT_ITEM.bytenr`);
3. **FS tree(s)** — a single leaf scan yields every `INODE_ITEM` (sizes) and `DIR_INDEX`
   (names + child object ids), which we assemble into the directory tree in memory.

The GMTA/btrfs-libs project reads btrfs *send-streams* (the output of `btrfs send`), which is a
different, higher-level format — not the on-disk B-trees — so it does not help here beyond its
(MIT) CRC32C, which the JDK already provides via `java.util.zip.CRC32C` (JDK 9+).

## Verification

Fixtures are produced unprivileged with `mkfs.btrfs -r <dir>` and cross-checked against
`btrfs inspect-internal dump-tree` / `dump-super`.
