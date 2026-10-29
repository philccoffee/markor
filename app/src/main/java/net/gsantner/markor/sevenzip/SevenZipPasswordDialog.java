/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.sevenzip;

import android.app.Activity;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;
import net.gsantner.opoc.wrapper.GsCallback;

import java.io.File;

/**
 * Password prompt shown before extracting a 7z archive.
 * <p>
 * The entered password is handed to the callback as a {@code char[]} so the caller can wipe it
 * from memory once it is no longer needed.
 */
public class SevenZipPasswordDialog {

    private SevenZipPasswordDialog() {
        //no instance
    }

    /**
     * Shows a password prompt for {@code archive}.
     *
     * @param callback receives the entered password (never {@code null}, but may be empty if the
     *                 archive turned out to be unencrypted)
     */
    public static void show(@NonNull final Activity activity,
                            @NonNull final File archive,
                            @NonNull final GsCallback.a1<char[]> callback) {
        show(activity, archive.getName(), callback);
    }

    /**
     * Shows a password prompt, displaying {@code displayName} rather than a file path.
     * Used when the archive is a temporary copy whose name would leak implementation details.
     */
    public static void show(@NonNull final Activity activity,
                            @NonNull final String displayName,
                            @NonNull final GsCallback.a1<char[]> callback) {
        final int horizontalPadding = (int) (20 * activity.getResources().getDisplayMetrics().density);

        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setHint(R.string.sevenzip_password_hint);

        final FrameLayout container = new FrameLayout(activity);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(horizontalPadding, 0, horizontalPadding, 0);
        container.addView(input, lp);

        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.sevenzip_password_title)
                .setMessage(activity.getString(R.string.sevenzip_password_message, displayName))
                .setView(container)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final char[] password = input.getText().toString().toCharArray();
                    callback.callback(password);
                })
                .show();
    }

    /**
     * Shows a password prompt for creating a new (packed) archive. An empty password is allowed and
     * yields an unencrypted archive.
     */
    public static void showForPacking(@NonNull final Activity activity,
                                      @NonNull final GsCallback.a1<char[]> callback) {
        final int horizontalPadding = (int) (20 * activity.getResources().getDisplayMetrics().density);

        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setHint(R.string.sevenzip_password_hint);

        final FrameLayout container = new FrameLayout(activity);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(horizontalPadding, 0, horizontalPadding, 0);
        container.addView(input, lp);

        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.sevenzip_pack_password_title)
                .setMessage(R.string.sevenzip_pack_password_message)
                .setView(container)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String text = input.getText().toString();
                    callback.callback(text.isEmpty() ? null : text.toCharArray());
                })
                .show();
    }

    /**
     * Shows a simple error dialog. Used when the 7z backend is not usable at all.
     */
    public static void notifyUnsupported(@NonNull final Activity activity, @Nullable final String message) {
        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.sevenzip_unavailable_title)
                .setMessage(message == null ? "" : message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
