package org.jnode.fs.btrfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jnode.util.LittleEndian;

/**
 * Reads btrfs file content from {@code EXTENT_DATA} items: <b>inline</b> extents (small files, data
 * embedded in the FS tree) and <b>regular</b> extents (pointing at a logical disk range). zlib
 * compression is decompressed with the JDK's {@link Inflater}; lzo/zstd are rejected with a clear
 * message (the file's size is still correct — it comes from the inode). Holes read as zeros.
 */
final class BtrfsFileContent {

    private BtrfsFileContent() {
    }

    /** One file extent, resolved from an EXTENT_DATA item. */
    static final class Extent {
        final long fileOffset; // where in the file this extent starts (key.offset)
        final long numBytes;   // bytes of the file this extent covers
        final int type;
        final int compression;
        final long diskBytenr; // logical addr of the on-disk extent (0 = hole), regular only
        final long diskNumBytes;
        final long dataOffset; // offset into the (decompressed) extent, regular only
        final byte[] inlineData; // inline only

        Extent(long fileOffset, long numBytes, int type, int compression, long diskBytenr,
                long diskNumBytes, long dataOffset, byte[] inlineData) {
            this.fileOffset = fileOffset;
            this.numBytes = numBytes;
            this.type = type;
            this.compression = compression;
            this.diskBytenr = diskBytenr;
            this.diskNumBytes = diskNumBytes;
            this.dataOffset = dataOffset;
            this.inlineData = inlineData;
        }
    }

    /** One FS-tree scan collecting this inode's extents (cache the result for repeated reads). */
    static List<Extent> collectExtents(BtrfsVolume vol, long subvolId, long objectId) throws IOException {
        final List<Extent> extents = new ArrayList<Extent>();
        long bytenr = vol.subvolBytenr(subvolId);
        vol.tree().scanLeaves(bytenr, (key, data) -> {
            if (key.getType() != BtrfsConstants.TYPE_EXTENT_DATA || key.getObjectId() != objectId) {
                return;
            }
            long fileOffset = key.getOffset();
            int compression = data[BtrfsConstants.EXTENT_COMPRESSION] & 0xFF;
            int type = data[BtrfsConstants.EXTENT_TYPE] & 0xFF;
            if (type == BtrfsConstants.EXTENT_TYPE_INLINE) {
                long ram = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_RAM_BYTES);
                byte[] inline = Arrays.copyOfRange(data, BtrfsConstants.EXTENT_INLINE_DATA, data.length);
                extents.add(new Extent(fileOffset, ram, type, compression, 0, 0, 0, inline));
            } else {
                long diskBytenr = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_DISK_BYTENR);
                long diskNumBytes = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_DISK_NUM_BYTES);
                long dataOffset = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_DATA_OFFSET);
                long numBytes = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_NUM_BYTES);
                extents.add(new Extent(fileOffset, numBytes, type, compression, diskBytenr,
                        diskNumBytes, dataOffset, null));
            }
        });
        return extents;
    }

    static int read(BtrfsVolume vol, long subvolId, long objectId, long fileSize, long fileOffset,
            byte[] dst, int off, int len) throws IOException {
        List<Extent> extents = collectExtents(vol, subvolId, objectId);
        return readFromExtents(vol, extents, fileSize, fileOffset, dst, off, len);
    }

    /** Fills {@code dst[off..off+len)} from {@code fileOffset}, using the extents (holes -> zeros). */
    static int readFromExtents(BtrfsVolume vol, List<Extent> extents, long fileSize, long fileOffset,
            byte[] dst, int off, int len) throws IOException {
        if (fileOffset >= fileSize) {
            return -1;
        }
        int want = (int) Math.min(len, fileSize - fileOffset);
        Arrays.fill(dst, off, off + want, (byte) 0); // default: hole (NO_HOLES leaves gaps unbacked)
        for (Extent e : extents) {
            long eStart = e.fileOffset;
            long eEnd = e.fileOffset + e.numBytes;
            long lo = Math.max(fileOffset, eStart);
            long hi = Math.min(fileOffset + want, eEnd);
            if (lo >= hi) {
                continue;
            }
            byte[] extentData = materialise(vol, e); // decompressed/plain bytes of the extent
            long inExtentBase = e.dataOffset + (lo - eStart); // where in extentData our slice begins
            for (long p = lo; p < hi; p++) {
                long src = inExtentBase + (p - lo);
                dst[off + (int) (p - fileOffset)] = (src < extentData.length) ? extentData[(int) src] : 0;
            }
        }
        return want;
    }

    /** The bytes of one extent (inline data or the on-disk range), decompressed if zlib. */
    private static byte[] materialise(BtrfsVolume vol, Extent e) throws IOException {
        if (e.type == BtrfsConstants.EXTENT_TYPE_INLINE) {
            return decompress(e.compression, e.inlineData, (int) e.numBytes);
        }
        if (e.diskBytenr == 0) {
            return new byte[0]; // an explicit hole
        }
        long physical = vol.chunkMap().toPhysical(e.diskBytenr);
        byte[] raw = vol.reader().read(physical, (int) e.diskNumBytes);
        // for a compressed extent, disk holds the whole compressed extent; decompress fully then slice
        return decompress(e.compression, raw, (int) (e.dataOffset + e.numBytes));
    }

    private static byte[] decompress(int compression, byte[] input, int expectedLen) throws IOException {
        switch (compression) {
            case BtrfsConstants.COMPRESS_NONE:
                return input;
            case BtrfsConstants.COMPRESS_ZLIB:
                return inflateZlib(input, Math.max(expectedLen, input.length));
            case BtrfsConstants.COMPRESS_LZO:
                throw new IOException("btrfs lzo-compressed content is not supported for reading");
            case BtrfsConstants.COMPRESS_ZSTD:
                throw new IOException("btrfs zstd-compressed content is not supported for reading");
            default:
                throw new IOException("btrfs unknown compression " + compression);
        }
    }

    private static byte[] inflateZlib(byte[] input, int hint) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(hint, 64));
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("btrfs zlib inflate failed: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }
}
