/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.sevenzip;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Orchestrates the Unpack / Pack flows for the app-private workspace.
 * <p>
 * Unpack: system file picker (SAF) -> optional password prompt -> extract into the workspace.
 * Pack:   workspace content -> inner {@code pack.zip} -> AES-256 encrypted outer archive,
 * written to the app's external files directory, then the workspace is cleared.
 */
public class WorkspaceController {

    private static final String TAG = "WorkspaceController";

    /** Timestamp format for packed archive names. */
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd-HH-mm-ss";

    /** MIME types offered by the system picker for Unpack. */
    private static final String[] ZIP_MIME_TYPES = {
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
            "*/*"
    };

    private final Activity _activity;
    private final AppSettings _appSettings;
    private final SevenZipArchive _archive;
    private final ActivityResultLauncher<String[]> _picker;
    private final Runnable _onWorkspaceChanged;

    /** Base name of the archive unpacked in this session, used to name the packed output. */
    @Nullable
    private String _lastUnpackedBaseName;

    /** Password of the archive unpacked in this session; {@code null} when unknown/not encrypted. */
    @Nullable
    private char[] _lastPassword;

    public WorkspaceController(@NonNull final Activity activity,
                               @NonNull final AppSettings appSettings,
                               @NonNull final ActivityResultLauncher<String[]> picker,
                               @NonNull final Runnable onWorkspaceChanged) {
        _activity = activity;
        _appSettings = appSettings;
        _archive = new Zip4jArchive();
        _picker = picker;
        _onWorkspaceChanged = onWorkspaceChanged;
    }

    // ---------------------------------------------------------------------------------------------
    // Unpack
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens the system file picker so the user can choose a ZIP archive to unpack.
     */
    public void startUnpack() {
        try {
            _picker.launch(ZIP_MIME_TYPES);
        } catch (final Exception e) {
            Log.e(TAG, "No file picker available", e);
            Toast.makeText(_activity, R.string.sevenzip_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Handles the URI returned by the system picker.
     */
    public void onArchivePicked(@Nullable final Uri uri) {
        if (uri == null) {
            return; // user cancelled
        }

        final String displayName = queryDisplayName(uri);
        if (TextUtils.isEmpty(displayName)) {
            Toast.makeText(_activity, R.string.sevenzip_unpack_failed, Toast.LENGTH_LONG).show();
            return;
        }

        // Copy the picked document into cache so we can hand a real File to the ZIP backend
        final File cacheCopy = new File(_activity.getCacheDir(), "unpack-" + System.currentTimeMillis() + "-" + displayName);
        try {
            GsFileUtils.copyUriToFile(_activity, uri, cacheCopy);
        } catch (final Exception e) {
            Log.e(TAG, "Cannot copy picked archive", e);
            Toast.makeText(_activity, R.string.sevenzip_unpack_failed, Toast.LENGTH_LONG).show();
            return;
        }

        if (!cacheCopy.isFile() || cacheCopy.length() == 0) {
            Toast.makeText(_activity, R.string.sevenzip_unpack_failed, Toast.LENGTH_LONG).show();
            return;
        }

        final boolean encrypted;
        try {
            encrypted = _archive.isEncrypted(cacheCopy);
        } catch (final SevenZipArchive.ArchiveException e) {
            Log.e(TAG, "Cannot inspect archive", e);
            deleteQuietly(cacheCopy);
            showError(R.string.sevenzip_unpack_failed, e.getMessage());
            return;
        }

        if (encrypted) {
            SevenZipPasswordDialog.show(_activity, displayName,
                    password -> extractPicked(cacheCopy, displayName, password));
        } else {
            extractPicked(cacheCopy, displayName, null);
        }
    }

    private void extractPicked(@NonNull final File archive, @NonNull final String displayName,
                               @Nullable final char[] password) {
        final AlertDialog progress = new AlertDialog.Builder(_activity,
                R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.sevenzip_unpacking)
                .setMessage(displayName)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            final SevenZipWorkspace workspace =
                    new SevenZipWorkspace(_activity.getApplicationContext(), _archive);
            try {
                workspace.extract(archive, password);
                _activity.runOnUiThread(() -> {
                    dismissQuietly(progress);
                    rememberSource(displayName, password);
                    _onWorkspaceChanged.run();
                    Toast.makeText(_activity, R.string.sevenzip_unpack_success, Toast.LENGTH_SHORT).show();
                });
            } catch (final SevenZipArchive.WrongPasswordException e) {
                _activity.runOnUiThread(() -> {
                    dismissQuietly(progress);
                    // Ask again — the user may have mistyped the password
                    SevenZipPasswordDialog.show(_activity, displayName,
                            retry -> extractPicked(archive, displayName, retry));
                });
            } catch (final SevenZipArchive.ArchiveException e) {
                Log.e(TAG, "Unpack failed", e);
                _activity.runOnUiThread(() -> {
                    dismissQuietly(progress);
                    showError(R.string.sevenzip_unpack_failed, e.getMessage());
                });
            } finally {
                deleteQuietly(archive);
            }
        }, "workspace-unpack").start();
    }

    // ---------------------------------------------------------------------------------------------
    // Pack
    // ---------------------------------------------------------------------------------------------

    /**
     * Packs the current workspace content into a new encrypted archive.
     * The user is asked for the password of the new archive; the output goes to the app's
     * external files directory.
     */
    public void startPack() {
        final File workspace = _appSettings.getWorkspaceDirectory();
        final String[] entries = workspace.list();
        if (entries == null || entries.length == 0) {
            Toast.makeText(_activity, R.string.sevenzip_pack_empty_workspace, Toast.LENGTH_LONG).show();
            return;
        }

        SevenZipPasswordDialog.showForPacking(_activity, password -> packWorkspace(password));
    }

    private void packWorkspace(@Nullable final char[] password) {
        final File workspace = _appSettings.getWorkspaceDirectory();
        final File outputDir = _appSettings.getWorkspaceOutputDirectory();
        final File output = buildOutputFile(outputDir);

        final AlertDialog progress = new AlertDialog.Builder(_activity,
                R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.sevenzip_packing)
                .setMessage(output.getName())
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                _archive.create(workspace, password, output);
                _activity.runOnUiThread(() -> {
                    dismissQuietly(progress);
                    showPackSuccess(output);
                });
            } catch (final SevenZipArchive.ArchiveException e) {
                Log.e(TAG, "Pack failed", e);
                _activity.runOnUiThread(() -> {
                    dismissQuietly(progress);
                    showError(R.string.sevenzip_pack_failed_title, e.getMessage());
                });
            }
        }, "workspace-pack").start();
    }

    private void showPackSuccess(@NonNull final File output) {
        new AlertDialog.Builder(_activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.sevenzip_pack_success_title)
                .setMessage(_activity.getString(R.string.sevenzip_pack_success_message,
                        output.getAbsolutePath()))
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // Only clear once the user acknowledged that the archive was written
                    final SevenZipWorkspace workspace =
                            new SevenZipWorkspace(_activity.getApplicationContext(), _archive);
                    workspace.clear();
                    _onWorkspaceChanged.run();
                })
                .show();
    }

    @NonNull
    private File buildOutputFile(@NonNull final File outputDir) {
        final String base = TextUtils.isEmpty(_lastUnpackedBaseName) ? "workspace" : _lastUnpackedBaseName;
        final String timestamp = new SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(new Date());
        return GsFileUtils.findNonConflictingDest(outputDir, base + "-" + timestamp + ".zip");
    }

    // ---------------------------------------------------------------------------------------------
    // Session state
    // ---------------------------------------------------------------------------------------------

    private void rememberSource(@NonNull final String displayName,
                                @Nullable final char[] password) {
        _lastUnpackedBaseName = stripZipExtension(displayName);
        if (_lastPassword != null) {
            java.util.Arrays.fill(_lastPassword, '\0');
        }
        _lastPassword = password;
    }

    @NonNull
    private static String stripZipExtension(@NonNull final String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    /**
     * Wipes the in-memory password. Call from the host activity's {@code onDestroy()}.
     */
    public void release() {
        if (_lastPassword != null) {
            java.util.Arrays.fill(_lastPassword, '\0');
            _lastPassword = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    @Nullable
    private String queryDisplayName(@NonNull final Uri uri) {
        try (final Cursor cursor = _activity.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (final Exception e) {
            Log.w(TAG, "Cannot query display name", e);
        }
        final String fallback = uri.getLastPathSegment();
        return TextUtils.isEmpty(fallback) ? null : fallback;
    }

    private void showError(final int titleRes, @Nullable final String message) {
        new AlertDialog.Builder(_activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(titleRes)
                .setMessage(TextUtils.isEmpty(message) ? _activity.getString(titleRes) : message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void dismissQuietly(@Nullable final AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private static void deleteQuietly(@Nullable final File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}

