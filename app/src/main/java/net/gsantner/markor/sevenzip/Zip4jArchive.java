/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.sevenzip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link SevenZipArchive} implementation based on zip4j (pure Java, AES-256 read + write).
 * <p>
 * <b>Two-layer archive layout</b>
 * <p>
 * The requirement asks for archives whose <i>file names</i> are not visible without the password.
 * Neither Commons Compress nor zip4j can encrypt entry names directly (that is a 7-Zip specific
 * feature and the 7-Zip CLI cannot be executed from an APK on Android 10+). The two-layer layout
 * achieves the same property structurally:
 * <pre>
 * outer.zip            AES-256 encrypted, password = user password
 *   └── pack.zip       inner archive holding the actual content
 *       ├── notes.md
 *       └── ...
 * </pre>
 * Because the entire inner archive is encrypted as a single opaque blob, its entry names are not
 * readable without the password — only the single name {@code pack.zip} is exposed.
 * <p>
 * When opening an archive, the content is extracted; if the result is exactly one {@code pack.zip},
 * it is unpacked as well, so archives produced by this app round-trip transparently and plain
 * single-layer archives (created by other tools) still work.
 */
public class Zip4jArchive implements SevenZipArchive {

    /** Name of the inner archive holding the actual content. */
    static final String INNER_ARCHIVE_NAME = "pack.zip";

    /** Maximum nesting we are willing to unwrap, protects against a pack.zip inside a pack.zip loop. */
    private static final int MAX_UNWRAP_DEPTH = 2;

    @Override
    public boolean isAvailable() {
        return true; // pure Java, always usable
    }

    @Override
    public boolean isEncrypted(@NonNull final File archive) throws ArchiveException {
        try {
            final ZipFile zip = new ZipFile(archive);
            return zip.isEncrypted();
        } catch (final ZipException e) {
            throw new ArchiveException("Cannot inspect archive: " + e.getMessage(), e);
        }
    }

    @Override
    public void extract(@NonNull final File archive, @Nullable final char[] password,
                        @NonNull final File targetDir) throws WrongPasswordException, ArchiveException {

        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new ArchiveException("Cannot create target directory: " + targetDir);
        }

        extractInto(archive, password, targetDir);
        unwrapNestedArchives(password, targetDir);
    }

    /**
     * Extracts {@code archive} straight into {@code targetDir} after validating entry paths.
     */
    private void extractInto(@NonNull final File archive, @Nullable final char[] password,
                             @NonNull final File targetDir) throws WrongPasswordException, ArchiveException {
        final ZipFile zip = password != null
                ? new ZipFile(archive, password)
                : new ZipFile(archive);

        try {
            if (!zip.isValidZipFile()) {
                throw new ArchiveException("Not a valid ZIP archive: " + archive.getName());
            }
            verifyNoPathTraversal(zip, targetDir);
            zip.extractAll(targetDir.getAbsolutePath());

        } catch (final ZipException e) {
            if (isWrongPassword(zip, e)) {
                throw new WrongPasswordException("Wrong password");
            }
            throw new ArchiveException("Extraction failed: " + e.getMessage(), e);
        }
    }

    /**
     * If the freshly extracted content consists of a single {@code pack.zip}, unpack that too and
     * flatten its content into {@code targetDir}.
     */
    private void unwrapNestedArchives(@Nullable final char[] password,
                                      @NonNull final File targetDir) throws WrongPasswordException, ArchiveException {
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
            final File[] children = targetDir.listFiles();
            if (children == null || children.length != 1) {
                return;
            }
            final File only = children[0];
            if (!only.isFile() || !INNER_ARCHIVE_NAME.equalsIgnoreCase(only.getName())) {
                return;
            }

            final File staging = new File(targetDir.getParentFile(), targetDir.getName() + "-unwrap");
            deleteRecursively(staging);
            if (!staging.mkdirs()) {
                throw new ArchiveException("Cannot create staging directory for inner archive");
            }

            try {
                extractInto(only, password, staging);
            } catch (final ArchiveException e) {
                // The inner archive is usually unencrypted; retry without a password before failing
                if (password != null) {
                    try {
                        extractInto(only, null, staging);
                    } catch (final ArchiveException | WrongPasswordException ignored) {
                        throw e;
                    }
                } else {
                    throw e;
                }
            }

            clearDirectory(targetDir);
            moveChildren(staging, targetDir);
            deleteRecursively(staging);
        }
    }

    @Override
    public void create(@NonNull final File sourceDir, @Nullable final char[] password,
                       @NonNull final File outputArchive) throws ArchiveException {

        if (!sourceDir.isDirectory()) {
            throw new ArchiveException("Source is not a directory: " + sourceDir);
        }
        if (outputArchive.exists() && !outputArchive.delete()) {
            throw new ArchiveException("Cannot overwrite existing archive: " + outputArchive);
        }

        final File staging = new File(outputArchive.getParentFile(), ".pack-staging");
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            throw new ArchiveException("Cannot create staging directory");
        }

        try {
            // ---- Layer 1: content -> pack.zip (unencrypted inner archive) ----
            final File innerArchive = new File(staging, INNER_ARCHIVE_NAME);
            final ZipFile innerZip = new ZipFile(innerArchive);
            final File[] children = sourceDir.listFiles();
            if (children != null) {
                for (final File child : children) {
                    if (child.isDirectory()) {
                        innerZip.addFolder(child);
                    } else {
                        final ZipParameters entryParams = new ZipParameters();
                        entryParams.setCompressionMethod(CompressionMethod.DEFLATE);
                        innerZip.addFile(child, entryParams);
                    }
                }
            }

            // ---- Layer 2: pack.zip -> output archive (AES-256, password protected) ----
            final ZipFile outputZip = password != null
                    ? new ZipFile(outputArchive, password)
                    : new ZipFile(outputArchive);

            final ZipParameters outerParams = new ZipParameters();
            outerParams.setCompressionMethod(CompressionMethod.DEFLATE);
            if (password != null) {
                outerParams.setEncryptFiles(true);
                outerParams.setEncryptionMethod(EncryptionMethod.AES);
                outerParams.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
            }
            outputZip.addFile(innerArchive, outerParams);

        } catch (final ZipException e) {
            throw new ArchiveException("Archive creation failed: " + e.getMessage(), e);
        } finally {
            deleteRecursively(staging);
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
            }
        }

        if (!outputArchive.isFile() || outputArchive.length() == 0) {
            throw new ArchiveException("Archive was not created: " + outputArchive);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Guards against path traversal: every entry must resolve inside {@code targetDir}.
     */
    private void verifyNoPathTraversal(@NonNull final ZipFile zip, @NonNull final File targetDir)
            throws ZipException, ArchiveException {
        final String canonicalRoot;
        try {
            canonicalRoot = targetDir.getCanonicalPath() + File.separator;
        } catch (final IOException e) {
            throw new ArchiveException("Cannot resolve target directory", e);
        }

        final List<FileHeader> headers = zip.getFileHeaders();
        for (final FileHeader header : headers) {
            final String entryName = header.getFileName();
            if (entryName == null || entryName.isEmpty()) {
                continue;
            }
            try {
                final File resolved = new File(targetDir, entryName).getCanonicalFile();
                if (!resolved.getPath().startsWith(canonicalRoot)) {
                    throw new ArchiveException("Archive contains an unsafe path: " + entryName);
                }
            } catch (final IOException e) {
                throw new ArchiveException("Cannot validate archive entry: " + entryName, e);
            }
        }
    }

    /**
     * zip4j signals a wrong password through a generic {@link ZipException}. We narrow it down by
     * checking the message, which is the most reliable option available across zip4j versions.
     */
    private static boolean isWrongPassword(@NonNull final ZipFile zip, @NonNull final ZipException e) {
        try {
            if (!zip.isEncrypted()) {
                return false;
            }
        } catch (final ZipException ignored) {
            return false;
        }
        final String message = e.getMessage();
        if (message == null) {
            return true; // encrypted archive failed to open, most likely the password
        }
        final String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("password")
                || lower.contains("wrong")
                || lower.contains("crc")
                || lower.contains("mac check")
                || lower.contains("invalid");
    }

    private static void moveChildren(@NonNull final File from, @NonNull final File to) throws ArchiveException {
        final File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        for (final File child : children) {
            final File dest = new File(to, child.getName());
            deleteRecursively(dest);
            if (!child.renameTo(dest)) {
                throw new ArchiveException("Cannot move extracted content into place: " + child.getName());
            }
        }
    }

    private static void clearDirectory(@NonNull final File dir) {
        final File[] children = dir.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
    }

    private static boolean deleteRecursively(@NonNull final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        return file.delete();
    }

    /**
     * @return the list of top-level entry names, used by tests and diagnostics.
     */
    @NonNull
    static List<String> listEntryNames(@NonNull final File archive, @Nullable final char[] password)
            throws ArchiveException {
        final ZipFile zip = password != null ? new ZipFile(archive, password) : new ZipFile(archive);
        final List<String> result = new ArrayList<>();
        try {
            for (final FileHeader header : zip.getFileHeaders()) {
                result.add(header.getFileName());
            }
        } catch (final ZipException e) {
            throw new ArchiveException("Cannot list archive: " + e.getMessage(), e);
        }
        return result;
    }
}
