package org.jnode.fs.btrfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import io.airlift.compress.zstd.ZstdDecompressor;
import org.jnode.util.LittleEndian;

/**
 * Reads btrfs file content from {@code EXTENT_DATA} items: <b>inline</b> extents (small files, data
 * embedded in the FS tree) and <b>regular</b> extents (pointing at a logical disk range). zlib
 * compression is decompressed with the JDK's {@link Inflater} and zstd with aircompressor's
 * pure-Java decoder; lzo is still rejected with a clear message (the file's size is always correct —
 * it comes from the inode). Holes read as zeros.
 */
final class BtrfsFileContent {

    private BtrfsFileContent() {
    }

    /** One file extent, resolved from an EXTENT_DATA item. */
    static final class Extent {
        final long fileOffset; // where in the file this extent starts (key.offset)
        final long numBytes;   // bytes of the file this extent covers
        final long ramBytes;   // uncompressed size of the whole extent (the decompress target)
        final int type;
        final int compression;
        final long diskBytenr; // logical addr of the on-disk extent (0 = hole), regular only
        final long diskNumBytes;
        final long dataOffset; // offset into the (decompressed) extent, regular only
        final byte[] inlineData; // inline only

        Extent(long fileOffset, long numBytes, long ramBytes, int type, int compression,
                long diskBytenr, long diskNumBytes, long dataOffset, byte[] inlineData) {
            this.fileOffset = fileOffset;
            this.numBytes = numBytes;
            this.ramBytes = ramBytes;
            this.type = type;
            this.compression = compression;
            this.diskBytenr = diskBytenr;
            this.diskNumBytes = diskNumBytes;
            this.dataOffset = dataOffset;
            this.inlineData = inlineData;
        }
    }

    /**
     * This inode's extents, via a keyed B-tree search: descend to the first {@code EXTENT_DATA} item
     * for the inode and walk forward while the key still matches (they are contiguous in key order),
     * instead of scanning the whole FS tree. Cache the result for repeated reads.
     */
    static List<Extent> collectExtents(BtrfsVolume vol, long subvolId, long objectId) throws IOException {
        final List<Extent> extents = new ArrayList<Extent>();
        long bytenr = vol.subvolBytenr(subvolId);
        BtrfsDiskKey start = new BtrfsDiskKey(objectId, BtrfsConstants.TYPE_EXTENT_DATA, 0);
        BtrfsTree.Cursor cur = vol.tree().search(bytenr, start);
        while (cur.valid()) {
            BtrfsDiskKey key = cur.key();
            if (key.getObjectId() != objectId || key.getType() != BtrfsConstants.TYPE_EXTENT_DATA) {
                break; // walked past this inode's extent range
            }
            byte[] data = cur.data();
            long fileOffset = key.getOffset();
            int compression = data[BtrfsConstants.EXTENT_COMPRESSION] & 0xFF;
            int type = data[BtrfsConstants.EXTENT_TYPE] & 0xFF;
            long ram = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_RAM_BYTES);
            if (type == BtrfsConstants.EXTENT_TYPE_INLINE) {
                byte[] inline = Arrays.copyOfRange(data, BtrfsConstants.EXTENT_INLINE_DATA, data.length);
                extents.add(new Extent(fileOffset, ram, ram, type, compression, 0, 0, 0, inline));
            } else {
                long diskBytenr = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_DISK_BYTENR);
                long diskNumBytes = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_DISK_NUM_BYTES);
                long dataOffset = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_DATA_OFFSET);
                long numBytes = LittleEndian.getInt64(data, BtrfsConstants.EXTENT_NUM_BYTES);
                extents.add(new Extent(fileOffset, numBytes, ram, type, compression, diskBytenr,
                        diskNumBytes, dataOffset, null));
            }
            cur.next();
        }
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

    /** The bytes of one extent (inline data or the on-disk range), decompressed if compressed. */
    private static byte[] materialise(BtrfsVolume vol, Extent e) throws IOException {
        if (e.type == BtrfsConstants.EXTENT_TYPE_INLINE) {
            return decompress(e.compression, e.inlineData, (int) e.ramBytes);
        }
        if (e.diskBytenr == 0) {
            return new byte[0]; // an explicit hole
        }
        long physical = vol.chunkMap().toPhysical(e.diskBytenr);
        byte[] raw = vol.reader().read(physical, (int) e.diskNumBytes);
        // for a compressed extent, disk holds the whole compressed extent; decompress fully then slice
        return decompress(e.compression, raw, (int) e.ramBytes);
    }

    /** Decompress a whole extent to its uncompressed length ({@code ram_bytes}); NONE returns input. */
    static byte[] decompress(int compression, byte[] input, int ramBytes) throws IOException {
        switch (compression) {
            case BtrfsConstants.COMPRESS_NONE:
                return input;
            case BtrfsConstants.COMPRESS_ZLIB:
                return inflateZlib(input, Math.max(ramBytes, input.length));
            case BtrfsConstants.COMPRESS_ZSTD:
                return inflateZstd(input, ramBytes);
            case BtrfsConstants.COMPRESS_LZO:
                throw new IOException("btrfs lzo-compressed content is not supported for reading");
            default:
                throw new IOException("btrfs unknown compression " + compression);
        }
    }

    /**
     * Decompress a zstd extent to its uncompressed length ({@code ramBytes}). btrfs stores the
     * compressed frame padded with zeros up to the sector size ({@code disk_num_bytes} is rounded
     * up); aircompressor's decoder loops {@code while (input < limit)} looking for more frames, so it
     * would reject that padding as a bad second frame. We first measure the frame's exact length
     * (walking its block headers — no decoding) and hand the decoder only those bytes.
     */
    private static byte[] inflateZstd(byte[] input, int ramBytes) throws IOException {
        if (ramBytes <= 0) {
            return new byte[0];
        }
        int frameLen = zstdFrameLength(input, input.length);
        byte[] out = new byte[ramBytes];
        try {
            int n = new ZstdDecompressor().decompress(input, 0, frameLen, out, 0, ramBytes);
            return n == ramBytes ? out : Arrays.copyOf(out, n);
        } catch (RuntimeException ex) {
            // aircompressor signals bad data with MalformedInputException (a RuntimeException)
            throw new IOException("btrfs zstd inflate failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * The exact on-disk byte length of the zstd frame at {@code input[0]} — magic + header + block
     * headers + optional content checksum — by walking the framing only (never decoding a block).
     * Lets us slice off btrfs's trailing sector padding before decoding. Frame format per RFC 8878.
     */
    static int zstdFrameLength(byte[] input, int limit) throws IOException {
        int p = 0;
        if (limit < 4) {
            throw new IOException("btrfs zstd: truncated frame");
        }
        long magic = (input[0] & 0xFFL) | ((input[1] & 0xFFL) << 8)
                | ((input[2] & 0xFFL) << 16) | ((input[3] & 0xFFL) << 24);
        if (magic != 0xFD2FB528L) {
            throw new IOException("btrfs zstd: not a frame (magic 0x" + Long.toHexString(magic) + ")");
        }
        p = 4;
        int fhd = input[p++] & 0xFF;                 // frame header descriptor
        int fcsFlag = (fhd >>> 6) & 0x3;             // frame-content-size field size selector
        boolean singleSegment = (fhd & 0x20) != 0;
        boolean hasChecksum = (fhd & 0x04) != 0;
        int didFlag = fhd & 0x3;                      // dictionary-id field size selector
        if (!singleSegment) {
            p += 1;                                   // window descriptor
        }
        p += (didFlag == 0) ? 0 : (didFlag == 1 ? 1 : (didFlag == 2 ? 2 : 4)); // dictionary id
        p += (fcsFlag == 0) ? (singleSegment ? 1 : 0) : (fcsFlag == 1 ? 2 : (fcsFlag == 2 ? 4 : 8));
        if (p > limit) {
            throw new IOException("btrfs zstd: truncated frame header");
        }
        boolean last = false;
        while (!last) {
            if (limit - p < 3) {
                throw new IOException("btrfs zstd: truncated block header");
            }
            int bh = (input[p] & 0xFF) | ((input[p + 1] & 0xFF) << 8) | ((input[p + 2] & 0xFF) << 16);
            p += 3;
            last = (bh & 0x1) != 0;
            int blockType = (bh >>> 1) & 0x3;         // 0=raw, 1=RLE, 2=compressed, 3=reserved
            int blockSize = bh >>> 3;
            if (blockType == 3) {
                throw new IOException("btrfs zstd: reserved block type");
            }
            p += (blockType == 1) ? 1 : blockSize;    // RLE stores one byte; raw/compressed store blockSize
            if (p > limit) {
                throw new IOException("btrfs zstd: truncated block");
            }
        }
        if (hasChecksum) {
            p += 4;
        }
        if (p > limit) {
            throw new IOException("btrfs zstd: truncated checksum");
        }
        return p;
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
