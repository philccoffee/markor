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

import java.io.File;

/**
 * Abstraction over the concrete 7-Zip backend, so that the UI layer does not depend on the
 * implementation (bundled CLI binary vs. native JNI binding) directly.
 * <p>
 * Implementations must support <b>header encryption</b> ({@code -mhe=on}), i.e. the archive file
 * names themselves are encrypted. This rules out pure-Java implementations such as
 * Apache Commons Compress, which cannot read header-encrypted archives and cannot write encrypted
 * archives at all.
 */
public interface SevenZipArchive {

    /**
     * Raised when the archive could not be opened because the supplied password was wrong.
     * Callers are expected to prompt for the password again.
     */
    class WrongPasswordException extends Exception {
        public WrongPasswordException(@Nullable String message) {
            super(message);
        }
    }

    /**
     * Raised for any other unpack/pack failure (corrupt archive, unsupported method, IO error...).
     */
    class ArchiveException extends Exception {
        public ArchiveException(@Nullable String message) {
            super(message);
        }

        public ArchiveException(@Nullable String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @return {@code true} if the backing implementation is usable on this device/build.
     * When this returns {@code false}, {@link #extract} and {@link #create} will throw
     * {@link ArchiveException}. The UI should surface a clear message instead of failing silently.
     */
    boolean isAvailable();

    /**
     * @return {@code true} if the given archive appears to be password protected.
     * Implementations should return {@code false} for non-encrypted archives so the UI can
     * skip the password prompt.
     */
    boolean isEncrypted(@NonNull File archive) throws ArchiveException;

    /**
     * Extracts the whole archive into {@code targetDir}.
     * <p>
     * Implementations <b>must</b> guard against path traversal: every entry path has to be
     * resolved and verified to stay inside {@code targetDir}.
     *
     * @param password password to use, or {@code null} for an unencrypted archive.
     * @throws WrongPasswordException if the password is incorrect
     * @throws ArchiveException       on any other failure
     */
    void extract(@NonNull File archive, @Nullable char[] password, @NonNull File targetDir)
            throws WrongPasswordException, ArchiveException;

    /**
     * Creates {@code outputArchive} containing the contents of {@code sourceDir}.
     *
     * @param password password used for AES encryption and header encryption, or {@code null}
     *                 to create an unencrypted archive.
     * @throws ArchiveException on failure
     */
    void create(@NonNull File sourceDir, @Nullable char[] password, @NonNull File outputArchive)
            throws ArchiveException;
}
