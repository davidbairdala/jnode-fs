package org.jnode.fs.btrfs;

import java.io.IOException;
import java.util.List;

/**
 * A handle to one inode in a {@link BtrfsVolume}: which subvolume it lives in, its object id, name,
 * kind and size. Lightweight (navigation looks children up in the subvolume's scanned maps), so a
 * large tree isn't materialised eagerly.
 */
public class BtrfsNode {

    private final BtrfsVolume volume;
    private final long subvolId;
    private final long objectId;
    private final String name;
    private final boolean directory;
    private final long size;

    BtrfsNode(BtrfsVolume volume, long subvolId, long objectId, String name, boolean directory, long size) {
        this.volume = volume;
        this.subvolId = subvolId;
        this.objectId = objectId;
        this.name = name;
        this.directory = directory;
        this.size = size;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    long getSubvolId() {
        return subvolId;
    }

    long getObjectId() {
        return objectId;
    }

    /** The directory's children (empty for a file). */
    public List<BtrfsNode> getChildren() throws IOException {
        return volume.listChildren(this);
    }

    /** Reads up to {@code dst.length} bytes of this file starting at {@code fileOffset}. */
    public int read(long fileOffset, byte[] dst, int off, int len) throws IOException {
        return BtrfsFileContent.read(volume, subvolId, objectId, size, fileOffset, dst, off, len);
    }
}
