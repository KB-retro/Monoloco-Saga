package com.monolocosaga;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * ROM hack patches. BPS names the ROM it was made for (the CRC32 of its source); IPS does not,
 * so {@link #ipsLikeness} guesses: the bytes an IPS writes around its changes mostly repeat the
 * ROM it was made from, and look random against any other.
 */
final class RomPatch {
    /** SNES copier header that some IPS patches count in their offsets. */
    static final int HEADER = 512;

    private RomPatch() { }

    static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    static boolean isBps(byte[] patch) {
        return patch.length >= 19 && patch[0] == 'B' && patch[1] == 'P' && patch[2] == 'S' && patch[3] == '1';
    }

    static boolean isIps(byte[] patch) {
        return patch.length >= 8 && patch[0] == 'P' && patch[1] == 'A' && patch[2] == 'T' && patch[3] == 'C'
            && patch[4] == 'H';
    }

    /** The CRC32 of the ROM a BPS patch applies to. */
    static long bpsSourceCrc(byte[] patch) {
        return readInt32(patch, patch.length - 12);
    }

    /** Applies a BPS patch, checking the source, the result and the patch itself. */
    static byte[] applyBps(byte[] source, byte[] patch) throws IOException {
        if (!isBps(patch)) throw new IOException("not a BPS patch");
        CRC32 crc = new CRC32();
        crc.update(patch, 0, patch.length - 4);
        if (crc.getValue() != readInt32(patch, patch.length - 4)) throw new IOException("damaged BPS patch");
        if (crc32(source) != bpsSourceCrc(patch)) throw new IOException("BPS patch for another ROM");
        int[] at = {4};
        long sourceSize = readNumber(patch, at), targetSize = readNumber(patch, at), metadata = readNumber(patch, at);
        if (sourceSize != source.length || targetSize > 64L * 1024 * 1024) throw new IOException("bad BPS sizes");
        at[0] += (int) metadata;
        byte[] target = new byte[(int) targetSize];
        int output = 0, sourceRelative = 0, targetRelative = 0;
        int end = patch.length - 12;
        while (at[0] < end) {
            long data = readNumber(patch, at);
            int command = (int) (data & 3);
            int length = (int) ((data >> 2) + 1);
            if (output + length > target.length) throw new IOException("BPS patch writes past the ROM");
            switch (command) {
                case 0:  // SourceRead
                    if (output + length > source.length) throw new IOException("bad BPS source read");
                    System.arraycopy(source, output, target, output, length);
                    break;
                case 1:  // TargetRead
                    if (at[0] + length > end) throw new IOException("short BPS patch");
                    System.arraycopy(patch, at[0], target, output, length);
                    at[0] += length;
                    break;
                case 2: {  // SourceCopy
                    long offset = readNumber(patch, at);
                    sourceRelative += (int) ((offset & 1) != 0 ? -(offset >> 1) : (offset >> 1));
                    if (sourceRelative < 0 || sourceRelative + length > source.length) throw new IOException("bad BPS source copy");
                    System.arraycopy(source, sourceRelative, target, output, length);
                    sourceRelative += length;
                    break;
                }
                default: {  // TargetCopy: may overlap the bytes it is writing
                    long offset = readNumber(patch, at);
                    targetRelative += (int) ((offset & 1) != 0 ? -(offset >> 1) : (offset >> 1));
                    if (targetRelative < 0 || targetRelative >= output + length) throw new IOException("bad BPS target copy");
                    for (int i = 0; i < length; i++) target[output + i] = target[targetRelative++];
                    break;
                }
            }
            output += length;
        }
        if (output != target.length || crc32(target) != readInt32(patch, patch.length - 8)) {
            throw new IOException("the BPS result does not check out");
        }
        return target;
    }

    /**
     * Applies an IPS patch. shift is HEADER when the patch was made for a ROM with a copier header
     * (its offsets count it), 0 otherwise.
     */
    static byte[] applyIps(byte[] source, byte[] patch, int shift) throws IOException {
        if (!isIps(patch)) throw new IOException("not an IPS patch");
        ByteArrayOutputStream ignored = null;
        byte[] target = source.clone();
        int at = 5;
        while (true) {
            if (at + 3 > patch.length) throw new IOException("short IPS patch");
            if (patch[at] == 'E' && patch[at + 1] == 'O' && patch[at + 2] == 'F') {
                at += 3;
                if (at + 3 <= patch.length) {
                    int truncate = readInt24(patch, at) - shift;
                    if (truncate > 0 && truncate < target.length) target = java.util.Arrays.copyOf(target, truncate);
                }
                return target;
            }
            if (at + 5 > patch.length) throw new IOException("short IPS record");
            int offset = readInt24(patch, at) - shift;
            int size = ((patch[at + 3] & 255) << 8) | (patch[at + 4] & 255);
            at += 5;
            int length;
            byte fill = 0;
            boolean rle = size == 0;
            if (rle) {
                if (at + 3 > patch.length) throw new IOException("short IPS run");
                length = ((patch[at] & 255) << 8) | (patch[at + 1] & 255);
                fill = patch[at + 2];
                at += 3;
            } else {
                if (at + size > patch.length) throw new IOException("short IPS data");
                length = size;
            }
            if (offset < 0) {
                // Writes into the copier header the ROM no longer has.
                if (!rle) at += size;
                continue;
            }
            if (offset + length > target.length) {
                if (offset + length > 64 * 1024 * 1024) throw new IOException("IPS patch too large");
                target = java.util.Arrays.copyOf(target, offset + length);
            }
            if (rle) java.util.Arrays.fill(target, offset, offset + length, fill);
            else System.arraycopy(patch, at, target, offset, length);
            if (!rle) at += size;
        }
    }

    /**
     * How well an IPS patch fits a ROM: the share of the bytes it writes that the ROM already
     * holds (IPS tools keep unchanged bytes between nearby changes). -1 when it does not apply.
     */
    static double ipsLikeness(byte[] source, byte[] patch, int shift) {
        if (!isIps(patch)) return -1;
        long same = 0, total = 0;
        int at = 5;
        while (at + 3 <= patch.length) {
            if (patch[at] == 'E' && patch[at + 1] == 'O' && patch[at + 2] == 'F') break;
            if (at + 5 > patch.length) return -1;
            int offset = readInt24(patch, at) - shift;
            int size = ((patch[at + 3] & 255) << 8) | (patch[at + 4] & 255);
            at += 5;
            if (size == 0) {
                at += 3;  // runs say little about the ROM underneath
                continue;
            }
            if (at + size > patch.length) return -1;
            for (int i = 0; i < size; i++) {
                int position = offset + i;
                if (position < 0 || position >= source.length) continue;
                total++;
                if (source[position] == patch[at + i]) same++;
            }
            at += size;
        }
        return total == 0 ? 0 : (double) same / total;
    }

    /**
     * Whether a ROM's internal header checksum adds up (HiROM: at 0xFFDC, complement then sum).
     * Hack editors usually fix it, so a patch that leaves it right was likely made for that ROM.
     */
    static boolean checksumValid(byte[] rom) {
        if (rom.length < 0x10000) return false;
        int sum = 0;
        for (byte b : rom) sum += b & 255;
        sum &= 0xFFFF;
        int complement = (rom[0xFFDC] & 255) | ((rom[0xFFDD] & 255) << 8);
        int stored = (rom[0xFFDE] & 255) | ((rom[0xFFDF] & 255) << 8);
        return stored == sum && (complement ^ stored) == 0xFFFF;
    }

    private static long readNumber(byte[] data, int[] at) throws IOException {
        long value = 0, shift = 1;
        while (true) {
            if (at[0] >= data.length) throw new IOException("short BPS patch");
            int x = data[at[0]++] & 255;
            value += (x & 0x7f) * shift;
            if ((x & 0x80) != 0) return value;
            shift <<= 7;
            value += shift;
        }
    }

    private static int readInt24(byte[] data, int at) {
        return ((data[at] & 255) << 16) | ((data[at + 1] & 255) << 8) | (data[at + 2] & 255);
    }

    private static long readInt32(byte[] data, int at) {
        return (data[at] & 255L) | ((data[at + 1] & 255L) << 8) | ((data[at + 2] & 255L) << 16)
            | ((data[at + 3] & 255L) << 24);
    }
}
