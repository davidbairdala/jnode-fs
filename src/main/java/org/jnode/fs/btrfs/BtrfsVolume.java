package org.jnode.fs.btrfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jnode.util.LittleEndian;

/**
 * A mounted (read-only) btrfs volume: superblock → chunk map → root tree → FS tree(s). Presents the
 * top-level subvolume (FS_TREE, objectid 5) as the root and descends into nested subvolumes, so a
 * disk-usage walk sees every subvolume's files. Each subvolume's FS tree is scanned once (lazily)
 * into small inode/children maps; navigation and file-content reads use those maps.
 */
public class BtrfsVolume {

    /** One directory entry as stored in the FS tree. */
    static final class DirEntry {
        final String name;
        final long childObjectId;
        final int locationType; // INODE_ITEM (a file/dir) or ROOT_ITEM (a subvolume)

        DirEntry(String name, long childObjectId, int locationType) {
            this.name = name;
            this.childObjectId = childObjectId;
            this.locationType = locationType;
        }
    }

    /** The scanned state of one subvolume's FS tree. */
    private static final class Subvol {
        final long rootDirId;
        final Map<Long, long[]> inodes = new HashMap<Long, long[]>();   // objectId -> {size, dirFlag}
        final Map<Long, List<DirEntry>> children = new HashMap<Long, List<DirEntry>>();

        Subvol(long rootDirId) {
            this.rootDirId = rootDirId;
        }
    }

    private final BtrfsBlockReader reader;
    private final BtrfsSuperblock sb;
    private final BtrfsChunkMap chunkMap;
    private final BtrfsTree tree;
    /** subvolume root objectid -> its FS tree bytenr (from the root tree's ROOT_ITEMs). */
    private final Map<Long, Long> subvolBytenr = new HashMap<Long, Long>();
    /** lazily scanned subvolumes, keyed by root objectid. */
    private final Map<Long, Subvol> scanned = new HashMap<Long, Subvol>();

    public BtrfsVolume(BtrfsBlockReader reader) throws IOException {
        this.reader = reader;
        this.sb = new BtrfsSuperblock(reader);
        if (sb.getNumDevices() != 1) {
            throw new IOException("Multi-device btrfs is not supported (" + sb.getNumDevices() + " devices)");
        }
        this.chunkMap = new BtrfsChunkMap(sb, reader);
        this.tree = new BtrfsTree(reader, chunkMap, sb.getNodeSize());
        readRootTree();
        if (!subvolBytenr.containsKey(BtrfsConstants.OBJECTID_FS_TREE)) {
            throw new IOException("btrfs has no FS tree");
        }
    }

    public BtrfsSuperblock getSuperblock() {
        return sb;
    }

    /** The volume root: the top-level subvolume's root directory. */
    public BtrfsNode getRoot() throws IOException {
        Subvol top = subvol(BtrfsConstants.OBJECTID_FS_TREE);
        return new BtrfsNode(this, BtrfsConstants.OBJECTID_FS_TREE, top.rootDirId, "", true, 0);
    }

    /** Children of a directory node (crossing into subvolumes where a dir entry points at one). */
    List<BtrfsNode> listChildren(BtrfsNode dir) throws IOException {
        List<BtrfsNode> result = new ArrayList<BtrfsNode>();
        Subvol sv = subvol(dir.getSubvolId());
        List<DirEntry> entries = sv.children.get(dir.getObjectId());
        if (entries == null) {
            return result;
        }
        for (DirEntry e : entries) {
            if (e.locationType == BtrfsConstants.TYPE_ROOT_ITEM) {
                // a nested subvolume: descend into its own FS tree
                Long bytenr = subvolBytenr.get(e.childObjectId);
                if (bytenr == null) {
                    continue; // subvolume not found (e.g. deleted) — skip
                }
                Subvol child = subvol(e.childObjectId);
                result.add(new BtrfsNode(this, e.childObjectId, child.rootDirId, e.name, true, 0));
            } else {
                long[] info = sv.inodes.get(e.childObjectId);
                if (info == null) {
                    continue;
                }
                boolean isDir = info[1] != 0;
                result.add(new BtrfsNode(this, dir.getSubvolId(), e.childObjectId, e.name, isDir, info[0]));
            }
        }
        return result;
    }

    // ---- scanning ----

    /** Collects every subvolume's FS-tree bytenr from the root tree's ROOT_ITEMs. */
    private void readRootTree() throws IOException {
        tree.scanLeaves(sb.getRootTreeLogical(), (key, data) -> {
            if (key.getType() == BtrfsConstants.TYPE_ROOT_ITEM) {
                long id = key.getObjectId();
                // subvolumes are FS_TREE (5) and objectids >= FIRST_FREE (256); skip the special
                // internal trees (extent/dev/csum/...) whose ids are < 256 and != 5
                if (id == BtrfsConstants.OBJECTID_FS_TREE || id >= BtrfsConstants.OBJECTID_FIRST_FREE) {
                    long bytenr = LittleEndian.getInt64(data, BtrfsConstants.ROOT_ITEM_BYTENR);
                    // keep the highest generation if duplicated (later ROOT_ITEM wins by scan order)
                    subvolBytenr.put(id, bytenr);
                }
            }
        });
    }

    /** Scans (once) a subvolume's FS tree into inode + children maps. */
    private Subvol subvol(long rootObjectId) throws IOException {
        Subvol existing = scanned.get(rootObjectId);
        if (existing != null) {
            return existing;
        }
        Long bytenr = subvolBytenr.get(rootObjectId);
        if (bytenr == null) {
            throw new IOException("Unknown btrfs subvolume " + rootObjectId);
        }
        long rootDirId = rootDirIdOf(rootObjectId);
        Subvol sv = new Subvol(rootDirId);
        scanned.put(rootObjectId, sv); // insert before scanning to guard against self-referential loops
        tree.scanLeaves(bytenr, (key, data) -> {
            int type = key.getType();
            if (type == BtrfsConstants.TYPE_INODE_ITEM) {
                long size = LittleEndian.getInt64(data, BtrfsConstants.INODE_SIZE_OFF);
                int mode = (int) LittleEndian.getUInt32(data, BtrfsConstants.INODE_MODE_OFF);
                long dirFlag = (mode & BtrfsConstants.S_IFMT) == BtrfsConstants.S_IFDIR ? 1 : 0;
                sv.inodes.put(key.getObjectId(), new long[] {size, dirFlag});
            } else if (type == BtrfsConstants.TYPE_DIR_INDEX) {
                DirEntry e = parseDirEntry(data);
                if (e != null) {
                    sv.children.computeIfAbsent(key.getObjectId(), k -> new ArrayList<DirEntry>()).add(e);
                }
            }
        });
        return sv;
    }

    /** The root directory inode id of a subvolume (from its ROOT_ITEM.dirid; defaults to 256). */
    private long rootDirIdOf(long rootObjectId) throws IOException {
        long[] dirId = {BtrfsConstants.OBJECTID_FIRST_FREE};
        tree.scanLeaves(sb.getRootTreeLogical(), (key, data) -> {
            if (key.getType() == BtrfsConstants.TYPE_ROOT_ITEM && key.getObjectId() == rootObjectId) {
                long d = LittleEndian.getInt64(data, BtrfsConstants.ROOT_ITEM_DIRID);
                if (d != 0) {
                    dirId[0] = d;
                }
            }
        });
        return dirId[0];
    }

    private static DirEntry parseDirEntry(byte[] data) {
        if (data.length < BtrfsConstants.DIR_NAME) {
            return null;
        }
        long childId = LittleEndian.getInt64(data, BtrfsConstants.DIR_LOCATION_OBJECTID);
        int locationType = data[BtrfsConstants.DIR_LOCATION_TYPE] & 0xFF;
        int nameLen = LittleEndian.getUInt16(data, BtrfsConstants.DIR_NAME_LEN);
        if (BtrfsConstants.DIR_NAME + nameLen > data.length) {
            return null;
        }
        String name = new String(data, BtrfsConstants.DIR_NAME, nameLen,
                java.nio.charset.StandardCharsets.UTF_8);
        return new DirEntry(name, childId, locationType);
    }

    // ---- file content ----

    BtrfsBlockReader reader() {
        return reader;
    }

    BtrfsChunkMap chunkMap() {
        return chunkMap;
    }

    BtrfsTree tree() {
        return tree;
    }

    long subvolBytenr(long subvolId) throws IOException {
        Long b = subvolBytenr.get(subvolId);
        if (b == null) {
            throw new IOException("Unknown subvolume " + subvolId);
        }
        return b;
    }

    int sectorSize() {
        return sb.getSectorSize();
    }
}
