package org.jnode.fs.btrfs;

import org.jnode.fs.FSDirectory;
import org.jnode.fs.spi.AbstractFSEntry;

/**
 * A btrfs directory entry, wrapping a {@link BtrfsNode}.
 */
public class BtrfsEntry extends AbstractFSEntry {

    private final BtrfsNode node;

    public BtrfsEntry(BtrfsNode node, String name, BtrfsFileSystem fs, FSDirectory parent) {
        super(fs, null, parent, name, entryType(name, node));
        this.node = node;
    }

    public BtrfsNode getNode() {
        return node;
    }

    private static int entryType(String name, BtrfsNode node) {
        if ("/".equals(name)) {
            return AbstractFSEntry.ROOT_ENTRY;
        }
        return node.isDirectory() ? AbstractFSEntry.DIR_ENTRY : AbstractFSEntry.FILE_ENTRY;
    }

    @Override
    public String getId() {
        return Long.toString(node.getObjectId());
    }
}
