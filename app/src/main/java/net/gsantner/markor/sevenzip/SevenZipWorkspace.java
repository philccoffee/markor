/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.sevenzip;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manages the 7z workspace: the app-private directory that holds the currently unpacked archive.
 * <p>
 * The workspace lives under {@link Context#getFilesDir()}, i.e.
 * {@code /data/data/<package>/files/workspace}, which is private to the app and not readable by
 * other apps or by the user via the file manager.
 * <p>
 * Lifecycle:
 * <ol>
 * <li>{@link #prepareClean()} - wipe leftovers from a previous session</li>
 * <li>{@link #extract} - unpack the selected archive into the workspace</li>
 * <li>... user edits files ...</li>
 * <li>{@link #buildOutputFile} + {@link SevenZipArchive#create} - pack back to a new archive</li>
 * <li>{@link #clear} - remove the workspace content</li>
 * </ol>
 */
public class SevenZipWorkspace {

    /** Directory (relative to {@link Context#getFilesDir()}) that holds the unpacked content. */
    private static final String WORKSPACE_DIR_NAME = "workspace";

    /** Timestamp format used for the packed archive name: yyyy-MM-dd-HH-mm-ss. */
    private static final String OUTPUT_TIMESTAMP_PATTERN = "yyyy-MM-dd-HH-mm-ss";

    private final File _workspaceDir;
    private final SevenZipArchive _archive;

    public SevenZipWorkspace(@NonNull final Context context, @NonNull final SevenZipArchive archive) {
        _workspaceDir = new File(context.getFilesDir(), WORKSPACE_DIR_NAME);
        _archive = archive;
    }

    /**
     * @return the app-private directory holding the unpacked archive content.
     */
    @NonNull
    public File getDirectory() {
        return _workspaceDir;
    }

    /**
     * @return the archive backend used by this workspace.
     */
    @NonNull
    public SevenZipArchive getArchive() {
        return _archive;
    }

    /**
     * Removes any content left over from a previous session and re-creates an empty workspace.
     * Must be called before {@link #extract} so that two different archives never get mixed.
     *
     * @return {@code true} if the workspace is empty and ready to be used.
     */
    public boolean prepareClean() {
        clear();
        return _workspaceDir.isDirectory() || _workspaceDir.mkdirs();
    }

    /**
     * Deletes everything inside the workspace directory. The directory itself is kept so that
     * callers holding a reference to it stay valid.
     *
     * @return {@code true} if the workspace is empty afterwards.
     */
    public boolean clear() {
        final File[] children = _workspaceDir.listFiles();
        if (children == null) {
            return !_workspaceDir.exists() || _workspaceDir.isDirectory();
        }
        boolean ok = true;
        for (final File child : children) {
            if (!deleteRecursively(child)) {
                ok = false;
            }
        }
        return ok;
    }

    /**
     * Unpacks {@code sourceArchive} into the workspace.
     * The workspace is wiped first, so a failure leaves no partially mixed content behind.
     *
     * @param password password, or {@code null} for an unencrypted archive
     */
    public void extract(@NonNull final File sourceArchive, @Nullable final char[] password)
            throws SevenZipArchive.WrongPasswordException, SevenZipArchive.ArchiveException {
        if (!prepareClean()) {
            throw new SevenZipArchive.ArchiveException("Cannot prepare workspace directory");
        }
        _archive.extract(sourceArchive, password, _workspaceDir);
    }

    /**
     * Builds the destination file for the packed archive: same directory as {@code sourceArchive},
     * named {@code <baseName>-yyyy-MM-dd-HH-mm-ss.7z}.
     * <p>
     * If an archive with that exact name already exists (two packs within the same second), a
     * numeric suffix is appended so nothing gets overwritten.
     *
     * @param sourceArchive the archive the workspace was unpacked from
     */
    @NonNull
    public static File buildOutputFile(@NonNull final File sourceArchive) {
        final File parent = sourceArchive.getParentFile();
        final String name = sourceArchive.getName();
        final String baseName = name.toLowerCase(Locale.ROOT).endsWith(".7z")
                ? name.substring(0, name.length() - ".7z".length())
                : name;

        final String timestamp = new SimpleDateFormat(OUTPUT_TIMESTAMP_PATTERN, Locale.US).format(new Date());
        final File dir = parent != null ? parent : sourceArchive.getAbsoluteFile().getParentFile();
        File candidate = new File(dir, baseName + "-" + timestamp + ".7z");

        int suffix = 1;
        while (candidate.exists() && suffix <= 99) {
            candidate = new File(dir, baseName + "-" + timestamp + "-" + suffix + ".7z");
            suffix++;
        }
        return candidate;
    }

    /**
     * Packs the current workspace content into a new archive at the given destination.
     *
     * @param password password used for AES + header encryption, or {@code null} to pack unencrypted
     */
    public void pack(@Nullable final char[] password, @NonNull final File outputArchive)
            throws SevenZipArchive.ArchiveException {
        _archive.create(_workspaceDir, password, outputArchive);
    }

    /**
     * @return {@code true} if the workspace currently holds at least one entry.
     */
    public boolean hasContent() {
        final String[] children = _workspaceDir.list();
        return children != null && children.length > 0;
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
}
