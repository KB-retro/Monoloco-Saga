package com.monolocosaga;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Imports what the player's folder holds: the three ROMs, found by size and SHA-256 whatever their
 * names (a 512-byte copier header is dropped), cores built on a PC (libmonoloco_gameN.so) and ROM
 * hacks of the three: ROMs already patched (same size, the game told by the title in their
 * header), BPS patches (they name the ROM they are for) and IPS patches (the player confirms the
 * game the first time; see {@link PendingIps}). The folder and one level of subfolders are searched.
 */
final class GameFiles {
    private static final int HEADER = 512;
    private static final long MAX_CORE = 512L * 1024 * 1024;
    private static final long MAX_PATCH = 16L * 1024 * 1024;
    /** The game title in the internal header of these (HiROM) ROMs. */
    private static final int TITLE = 0xFFC0, TITLE_LENGTH = 21;

    interface Progress {
        /** fraction 0..1 of all bytes to copy. */
        void update(float fraction);
    }

    /** What the scan found. */
    static final class Scan {
        final boolean[] roms = new boolean[Games.COUNT + 1];
        final boolean[] cores = new boolean[Games.COUNT + 1];
        /** "game\tname" of each hack installed. */
        final List<String> hacks = new ArrayList<>();
        /** IPS patches the player has not assigned to a game yet. */
        final List<PendingIps> pendingIps = new ArrayList<>();
        int unknownRoms;
        int badCores;
        /** Patches for none of the three ROMs, damaged, or growing the ROM. */
        int badPatches;

        boolean any() {
            for (int i = 1; i <= Games.COUNT; i++) if (roms[i] || cores[i]) return true;
            return !hacks.isEmpty() || !pendingIps.isEmpty();
        }
    }

    /** An IPS patch to ask about, with the game it fits best. */
    static final class PendingIps {
        String name;
        byte[] patch;
        long crc;
        int suggestedGame;
    }

    private static final class Candidate {
        Uri uri;
        String name;
        long size;
        boolean core;
        boolean patch;
        int game;
    }

    private GameFiles() { }

    static Scan importFrom(Context context, Uri tree, Progress progress) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        List<Candidate> found = new ArrayList<>();
        String root = DocumentsContract.getTreeDocumentId(tree);
        list(resolver, tree, root, found, 1);
        long total = 0;
        for (Candidate candidate : found) total += candidate.size;
        Scan scan = new Scan();
        long[] done = {0};
        List<File> modified = new ArrayList<>();
        List<String> modifiedNames = new ArrayList<>();
        List<Candidate> patches = new ArrayList<>();
        for (Candidate candidate : found) {
            if (candidate.core) {
                if (scan.cores[candidate.game]) continue;
                if (copyCore(context, candidate, progress, done, total)) scan.cores[candidate.game] = true;
                else scan.badCores++;
            } else if (candidate.patch) {
                patches.add(candidate);
            } else {
                File temporary = new File(Games.appDir(context), ".import" + modified.size() + ".tmp");
                int game = copyRom(context, candidate, temporary, progress, done, total);
                if (game > 0) {
                    scan.roms[game] = true;
                } else {
                    modified.add(temporary);
                    modifiedNames.add(baseName(candidate.name));
                }
            }
        }
        // Hacks go on top of the verified ROMs, so these come after all of them.
        Retail retail = new Retail(context);
        for (int i = 0; i < modified.size(); i++) {
            File rom = modified.get(i);
            int game = retail.gameWithTitle(readTitle(rom));
            if (game > 0) {
                installHack(context, game, modifiedNames.get(i), rom);
                scan.hacks.add(game + "\t" + modifiedNames.get(i));
            } else {
                rom.delete();
                scan.unknownRoms++;
            }
        }
        SharedPreferences prefs = LauncherSettings.prefs(context);
        for (Candidate candidate : patches) {
            byte[] patch = read(context, candidate.uri, candidate.size);
            done[0] += candidate.size;
            progress.update(total > 0 ? Math.min(1f, (float) done[0] / total) : 1);
            String name = baseName(candidate.name);
            if (RomPatch.isBps(patch)) {
                int game = retail.gameWithCrc(RomPatch.bpsSourceCrc(patch));
                byte[] rom = null;
                try {
                    if (game > 0) rom = RomPatch.applyBps(retail.rom(game), patch);
                } catch (IOException exception) {
                    android.util.Log.w("Monoloco", "BPS patch " + candidate.name, exception);
                }
                if (rom != null && rom.length == Games.ROM_SIZE) {
                    installHack(context, game, name, rom);
                    scan.hacks.add(game + "\t" + name);
                } else {
                    scan.badPatches++;
                }
            } else if (RomPatch.isIps(patch)) {
                long crc = RomPatch.crc32(patch);
                int answer = prefs.getInt(ipsKey(crc), -1);
                if (answer == 0) continue;  // the player said it is for none of them
                if (answer > 0) {
                    if (retail.has(answer) && installIps(context, retail, answer, name, patch)) {
                        scan.hacks.add(answer + "\t" + name);
                    } else {
                        scan.badPatches++;
                    }
                    continue;
                }
                PendingIps pending = new PendingIps();
                pending.name = name;
                pending.patch = patch;
                pending.crc = crc;
                pending.suggestedGame = suggestGame(retail, patch);
                if (pending.suggestedGame > 0) scan.pendingIps.add(pending);
                else scan.badPatches++;
            } else {
                scan.badPatches++;
            }
        }
        progress.update(1);
        return scan;
    }

    /** Applies an IPS patch the player assigned to a game; remembers the answer (0: none). */
    static boolean assignIps(Context context, PendingIps pending, int game) throws IOException {
        LauncherSettings.prefs(context).edit().putInt(ipsKey(pending.crc), game).apply();
        if (game <= 0) return true;
        Retail retail = new Retail(context);
        return retail.has(game) && installIps(context, retail, game, pending.name, pending.patch);
    }

    /**
     * The game an IPS patch most likely belongs to: the one whose header checksum still adds up
     * once patched (editors fix it), else the one whose bytes the patch repeats most; 0 if none fits.
     */
    private static int suggestGame(Retail retail, byte[] patch) throws IOException {
        int best = 0;
        double bestScore = -1;
        for (int game = 1; game <= Games.COUNT; game++) {
            if (!retail.has(game)) continue;
            for (int shift : new int[] {0, RomPatch.HEADER}) {
                double likeness = RomPatch.ipsLikeness(retail.rom(game), patch, shift);
                if (likeness < 0) continue;
                double score = likeness;
                try {
                    byte[] patched = RomPatch.applyIps(retail.rom(game), patch, shift);
                    if (patched.length != Games.ROM_SIZE) continue;
                    if (RomPatch.checksumValid(patched)) score += 10;
                } catch (IOException exception) {
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = game;
                }
            }
        }
        return best;
    }

    private static String ipsKey(long crc) { return "ips_" + Long.toHexString(crc); }

    /** With or without the copier header the patch may count, whichever fits the ROM better. */
    private static boolean installIps(Context context, Retail retail, int game, String name, byte[] patch)
            throws IOException {
        byte[] source = retail.rom(game);
        int shift = RomPatch.ipsLikeness(source, patch, RomPatch.HEADER) > RomPatch.ipsLikeness(source, patch, 0)
            ? RomPatch.HEADER : 0;
        byte[] rom;
        try {
            rom = RomPatch.applyIps(source, patch, shift);
        } catch (IOException exception) {
            android.util.Log.w("Monoloco", "IPS patch " + name, exception);
            return false;
        }
        if (rom.length != Games.ROM_SIZE) return false;  // expanded ROMs need code the cores lack
        installHack(context, game, name, rom);
        return true;
    }

    /** The verified ROMs, read when a hack needs them. */
    private static final class Retail {
        private final Context context;
        private final byte[][] roms = new byte[Games.COUNT + 1][];
        private final long[] crcs = new long[Games.COUNT + 1];

        Retail(Context context) { this.context = context; }

        boolean has(int game) { return Games.hasRom(context, game); }

        byte[] rom(int game) throws IOException {
            if (roms[game] == null) roms[game] = readFile(Games.rom(context, game));
            return roms[game];
        }

        int gameWithTitle(byte[] title) throws IOException {
            if (title == null) return 0;
            for (int game = 1; game <= Games.COUNT; game++) {
                if (has(game) && java.util.Arrays.equals(title, readTitle(Games.rom(context, game)))) return game;
            }
            return 0;
        }

        int gameWithCrc(long crc) throws IOException {
            for (int game = 1; game <= Games.COUNT; game++) {
                if (!has(game)) continue;
                if (crcs[game] == 0) crcs[game] = RomPatch.crc32(rom(game));
                if (crcs[game] == crc) return game;
            }
            return 0;
        }
    }

    /** Puts a hack in its version folder, named after its file. */
    private static void installHack(Context context, int game, String name, Object rom) throws IOException {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        if (id.length() > 60) id = id.substring(0, 60);
        if (id.isEmpty() || id.startsWith(".")) id = "hack" + id;
        File folder = Games.versionDir(context, game, id);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create " + folder);
        File destination = new File(folder, "rom.sfc");
        if (rom instanceof File) {
            install((File) rom, destination);
        } else {
            File temporary = new File(folder, "rom.sfc.tmp");
            try (OutputStream output = new FileOutputStream(temporary)) {
                output.write((byte[]) rom);
            }
            install(temporary, destination);
        }
        try (OutputStream output = new FileOutputStream(new File(folder, "name.txt"))) {
            output.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.trim().isEmpty() ? name : base.trim();
    }

    private static byte[] readTitle(File rom) throws IOException {
        if (rom.length() < TITLE + TITLE_LENGTH) return null;
        byte[] title = new byte[TITLE_LENGTH];
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(rom, "r")) {
            file.seek(TITLE);
            file.readFully(title);
        }
        return title;
    }

    private static byte[] readFile(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (InputStream input = new java.io.FileInputStream(file)) {
            int at = 0, count;
            while (at < data.length && (count = input.read(data, at, data.length - at)) > 0) at += count;
            if (at != data.length) throw new IOException("Short read of " + file);
        }
        return data;
    }

    private static byte[] read(Context context, Uri document, long size) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(document)) {
            if (input == null) throw new IOException("Cannot open " + document);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream((int) Math.max(0, size));
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = input.read(buffer)) > 0) {
                output.write(buffer, 0, count);
                if (output.size() > MAX_PATCH) throw new IOException("Patch too large");
            }
            return output.toByteArray();
        }
    }

    private static void list(ContentResolver resolver, Uri tree, String parent, List<Candidate> into, int depth)
            throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE};
        List<String> folders = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot list the folder");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1) != null ? cursor.getString(1) : "";
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(3))) {
                    folders.add(id);
                    continue;
                }
                long size = cursor.getLong(2);
                Candidate candidate = new Candidate();
                candidate.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                candidate.name = name;
                candidate.size = size;
                String lower = name.toLowerCase(Locale.ROOT);
                if (size == Games.ROM_SIZE || size == Games.ROM_SIZE + HEADER) {
                    into.add(candidate);
                } else if ((lower.endsWith(".ips") || lower.endsWith(".bps")) && size > 0 && size <= MAX_PATCH) {
                    candidate.patch = true;
                    into.add(candidate);
                } else if (lower.endsWith(".so") && size > 0 && size < MAX_CORE) {
                    for (int game = 1; game <= Games.COUNT; game++) {
                        if (lower.contains("game" + game)) {
                            candidate.core = true;
                            candidate.game = game;
                            into.add(candidate);
                            break;
                        }
                    }
                }
            }
        }
        if (depth > 0) for (String folder : folders) list(resolver, tree, folder, into, depth - 1);
    }

    /**
     * Returns the game the ROM belongs to, or 0 when it is not one of the three verified ones; its
     * copy (without a copier header) is then left in temporary.
     */
    private static int copyRom(Context context, Candidate rom, File temporary, Progress progress, long[] done,
            long total) throws IOException {
        File parent = temporary.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        String sha = copy(context, rom.uri, temporary, rom.size == Games.ROM_SIZE + HEADER ? HEADER : 0,
                progress, done, total);
        for (int game = 1; game <= Games.COUNT; game++) {
            if (!Games.SHA256[game - 1].equals(sha)) continue;
            File destination = Games.rom(context, game);
            install(temporary, destination);
            return game;
        }
        return 0;
    }

    private static boolean copyCore(Context context, Candidate core, Progress progress, long[] done, long total)
            throws IOException {
        File destination = Games.importedCore(context, core.game);
        File temporary = new File(destination.getPath() + ".tmp");
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        copy(context, core.uri, temporary, 0, progress, done, total);
        if (!isArm64Elf(temporary)) {
            temporary.delete();
            return false;
        }
        if (destination.exists()) {
            destination.setWritable(true);
            if (!destination.delete()) throw new IOException("Cannot replace " + destination);
        }
        if (!temporary.renameTo(destination)) throw new IOException("Cannot write " + destination);
        // Android 14+ only loads native code from read-only files.
        destination.setReadOnly();
        return true;
    }

    private static boolean isArm64Elf(File file) {
        byte[] header = new byte[20];
        try (InputStream input = new java.io.FileInputStream(file)) {
            if (input.read(header) != header.length) return false;
        } catch (IOException exception) {
            return false;
        }
        // ELF, 64-bit, little-endian, shared object, machine 183 (AArch64).
        return header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
            && header[4] == 2 && header[5] == 1 && header[16] == 3 && (header[18] & 255) == 183;
    }

    private static void install(File temporary, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace " + destination);
        if (!temporary.renameTo(destination)) throw new IOException("Cannot write " + destination);
    }

    /** Copies a document, skipping its first bytes, and returns the SHA-256 of what was written. */
    private static String copy(Context context, Uri document, File destination, int skip, Progress progress,
            long[] done, long total) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(exception);
        }
        byte[] buffer = new byte[1 << 16];
        long lastUpdate = 0;
        try (InputStream input = context.getContentResolver().openInputStream(document);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Cannot open " + document);
            long skipped = 0;
            while (skipped < skip) {
                long n = input.skip(skip - skipped);
                if (n <= 0) throw new IOException("Short file");
                skipped += n;
            }
            done[0] += skip;
            int count;
            while ((count = input.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                done[0] += count;
                long now = android.os.SystemClock.elapsedRealtime();
                if (total > 0 && now - lastUpdate > 100) {
                    lastUpdate = now;
                    progress.update(Math.min(1f, (float) done[0] / total));
                }
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
