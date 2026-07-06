package org.jnode.fs.btrfs;

import java.io.IOException;
import java.util.Arrays;

import org.jnode.util.LittleEndian;

/**
 * Reads btrfs B-trees. A tree block is one {@code nodeSize} region at a logical address; its header
 * gives the level. Level 0 is a leaf (an array of {@code (key, offset, size)} items whose data
 * follows the header); level &gt; 0 is an internal node (key pointers to child blocks). A full
 * {@link #scanLeaves} visits every item in the tree — which is all the read-only reader needs
 * (collect the FS tree's inodes and directory entries in one pass).
 */
public class BtrfsTree {

    /** Receives each leaf item: its key and a copy of its data bytes. */
    public interface ItemVisitor {
        void item(BtrfsDiskKey key, byte[] itemData) throws IOException;
    }

    private final BtrfsBlockReader reader;
    private final BtrfsChunkMap chunkMap;
    private final int nodeSize;
    /** Depth guard against a corrupt/looping tree. */
    private static final int MAX_LEVEL = 16;

    public BtrfsTree(BtrfsBlockReader reader, BtrfsChunkMap chunkMap, int nodeSize) {
        this.reader = reader;
        this.chunkMap = chunkMap;
        this.nodeSize = nodeSize;
    }

    /** Reads one tree block (node or leaf) at a logical address. */
    public byte[] readBlock(long logical) throws IOException {
        long physical = chunkMap.toPhysical(logical);
        return reader.read(physical, nodeSize);
    }

    /** Visits every item in the tree rooted at {@code rootLogical}. */
    public void scanLeaves(long rootLogical, ItemVisitor visitor) throws IOException {
        scan(rootLogical, visitor, 0);
    }

    private void scan(long logical, ItemVisitor visitor, int depth) throws IOException {
        if (depth > MAX_LEVEL) {
            throw new IOException("btrfs tree too deep (corrupt?)");
        }
        byte[] block = readBlock(logical);
        int nrItems = (int) LittleEndian.getUInt32(block, BtrfsConstants.HDR_NRITEMS);
        int level = block[BtrfsConstants.HDR_LEVEL] & 0xFF;

        if (level == 0) {
            for (int i = 0; i < nrItems; i++) {
                int itemPos = BtrfsConstants.HEADER_SIZE + i * BtrfsConstants.ITEM_SIZE;
                if (itemPos + BtrfsConstants.ITEM_SIZE > block.length) {
                    break;
                }
                BtrfsDiskKey key = new BtrfsDiskKey(block, itemPos);
                int dataOff = (int) LittleEndian.getUInt32(block, itemPos + BtrfsConstants.KEY_SIZE);
                int dataLen = (int) LittleEndian.getUInt32(block, itemPos + BtrfsConstants.KEY_SIZE + 4);
                int start = BtrfsConstants.HEADER_SIZE + dataOff;
                if (start < 0 || dataLen < 0 || start + dataLen > block.length) {
                    continue; // guard against a corrupt item pointer
                }
                visitor.item(key, Arrays.copyOfRange(block, start, start + dataLen));
            }
        } else {
            for (int i = 0; i < nrItems; i++) {
                int ptrPos = BtrfsConstants.HEADER_SIZE + i * BtrfsConstants.KEY_PTR_SIZE;
                if (ptrPos + BtrfsConstants.KEY_PTR_SIZE > block.length) {
                    break;
                }
                long childLogical = LittleEndian.getInt64(block, ptrPos + BtrfsConstants.KEY_SIZE);
                scan(childLogical, visitor, depth + 1);
            }
        }
    }
}
