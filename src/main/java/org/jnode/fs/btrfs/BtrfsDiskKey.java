package org.jnode.fs.btrfs;

import org.jnode.util.LittleEndian;

/** A btrfs key: {@code (objectid, type, offset)} — 17 bytes, the ordering key of every B-tree. */
public class BtrfsDiskKey {

    private final long objectId;
    private final int type;
    private final long offset;

    public BtrfsDiskKey(byte[] buf, int pos) {
        objectId = LittleEndian.getInt64(buf, pos);
        type = buf[pos + 8] & 0xFF;
        offset = LittleEndian.getInt64(buf, pos + 9);
    }

    public long getObjectId() {
        return objectId;
    }

    public int getType() {
        return type;
    }

    public long getOffset() {
        return offset;
    }

    @Override
    public String toString() {
        return "(" + objectId + " type=" + type + " off=" + offset + ")";
    }
}
