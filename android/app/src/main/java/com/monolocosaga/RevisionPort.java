package com.monolocosaga;

/**
 * Moves a hack made for another revision of a game (say 1.2) onto the revision the cores were
 * compiled from (1.0). Revisions share almost all their bytes, though some blocks moved: each
 * change the hack makes to its revision is placed where the same original bytes, with the same
 * bytes around them, sit in ours. A change whose original bytes are not in ours (code or data the
 * revisions do differently) is left out and counted.
 */
final class RevisionPort {
    /** Unchanged bytes on each side of a change that must match too. */
    private static final int CONTEXT = 24;
    /** Changes this close together move as one. */
    private static final int GAP = 8;

    static final class Result {
        byte[] rom;
        int changes, placed;

        int percent() { return changes == 0 ? 100 : (int) (100L * placed / changes); }
    }

    private RevisionPort() { }

    /** The revision byte of a ROM's internal header (0 for 1.0, 2 for 1.2), or -1. */
    static int revision(byte[] rom) {
        return rom.length > 0xFFDB ? rom[0xFFDB] & 255 : -1;
    }

    /**
     * base: the clean ROM the hack was made for; modified: base with the hack; target: our clean
     * revision. Returns null when the hack changes the ROM's size.
     */
    static Result port(byte[] base, byte[] modified, byte[] target) {
        if (modified.length != base.length || target.length != base.length) return null;
        Result result = new Result();
        result.rom = target.clone();
        int delta = 0;
        int i = 0;
        while (i < base.length) {
            if (base[i] == modified[i]) {
                i++;
                continue;
            }
            int start = i, end = i + 1, last = i;
            for (int j = i + 1; j < base.length && j <= last + GAP; j++) {
                if (base[j] != modified[j]) {
                    last = j;
                    end = j + 1;
                }
            }
            i = end;
            result.changes++;
            int from = Math.max(0, start - CONTEXT), to = Math.min(base.length, end + CONTEXT);
            Integer found = null;
            if (matches(base, target, from, to, delta)) found = delta;
            else if (delta != 0 && matches(base, target, from, to, 0)) found = 0;
            else found = search(base, target, from, to, start);
            if (found == null) continue;
            delta = found;
            System.arraycopy(modified, start, result.rom, start + delta, end - start);
            result.placed++;
        }
        return result;
    }

    /** Whether base[from, to) sits in target at from + delta. */
    private static boolean matches(byte[] base, byte[] target, int from, int to, int delta) {
        if (from + delta < 0 || to + delta > target.length) return false;
        for (int j = from; j < to; j++) if (base[j] != target[j + delta]) return false;
        return true;
    }

    /** Where base[from, to) sits in target, nearest to its own place; null when nowhere. */
    private static Integer search(byte[] base, byte[] target, int from, int to, int near) {
        int length = to - from;
        if (length < 2 * CONTEXT) return null;  // too little to tell where it belongs
        // Boyer-Moore-Horspool over the whole ROM.
        int[] shift = new int[256];
        java.util.Arrays.fill(shift, length);
        for (int k = 0; k < length - 1; k++) shift[base[from + k] & 255] = length - 1 - k;
        Integer best = null;
        int at = 0;
        while (at <= target.length - length) {
            int k = length - 1;
            while (k >= 0 && target[at + k] == base[from + k]) k--;
            if (k < 0) {
                int delta = at - from;
                if (best == null || Math.abs(delta + from - near) < Math.abs(best + from - near)) best = delta;
                at++;
            } else {
                at += shift[target[at + length - 1] & 255];
            }
        }
        return best;
    }
}
