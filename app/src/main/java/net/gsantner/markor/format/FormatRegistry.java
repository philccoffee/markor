/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.format;

import android.content.Context;
import android.text.InputFilter;
import android.text.TextWatcher;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import net.gsantner.markor.R;
import net.gsantner.markor.format.markdown.MarkdownActionButtons;
import net.gsantner.markor.format.markdown.MarkdownReplacePatternGenerator;
import net.gsantner.markor.format.markdown.MarkdownSyntaxHighlighter;
import net.gsantner.markor.format.markdown.MarkdownTextConverter;
import net.gsantner.markor.format.plaintext.PlaintextActionButtons;
import net.gsantner.markor.format.plaintext.PlaintextSyntaxHighlighter;
import net.gsantner.markor.format.plaintext.PlaintextTextConverter;
import net.gsantner.markor.frontend.textview.AutoTextFormatter;
import net.gsantner.markor.frontend.textview.ListHandler;
import net.gsantner.markor.frontend.textview.SyntaxHighlighterBase;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.markor.model.Document;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Registry of the text formats supported by this (trimmed-down) Markdown-only build.
 * <p>
 * Only two formats remain:
 * <ul>
 * <li>{@link #FORMAT_MARKDOWN} - full Markdown editing / rendering (primary format)</li>
 * <li>{@link #FORMAT_PLAIN} - plain text fallback for any other text file</li>
 * </ul>
 * All other legacy formats (TODO.txt, CSV, WikiText, AsciiDoc, Orgmode, KeyValue,
 * EmbedBinary) have been removed from this build. The {@code FORMAT_*} constants are
 * kept so that leftover resource / preference references keep compiling; they are all
 * mapped to {@link #FORMAT_PLAIN} at runtime.
 */
public class FormatRegistry {
    public static final int FORMAT_UNKNOWN = 0;
    public static final int FORMAT_MARKDOWN = R.string.action_format_markdown;
    public static final int FORMAT_PLAIN = R.string.action_format_plaintext;

    public final static MarkdownTextConverter CONVERTER_MARKDOWN = new MarkdownTextConverter();
    public final static PlaintextTextConverter CONVERTER_PLAINTEXT = new PlaintextTextConverter();

    // File extensions that are known not to be supported
    private static final List<String> EXTERNAL_FILE_EXTENSIONS = Collections.singletonList(".pdf");

    public static class Format {
        public final @StringRes int format, name;
        public final String defaultExtensionWithDot;
        public final TextConverterBase converter;

        public Format(@StringRes final int a_format, @StringRes final int a_name, final String a_defaultFileExtension, final TextConverterBase a_converter) {
            format = a_format;
            name = a_name;
            defaultExtensionWithDot = a_defaultFileExtension;
            converter = a_converter;
        }
    }

    // Order here is used to **determine** format by its file extension and/or content heading
    public static final List<Format> FORMATS = Arrays.asList(
            new Format(FormatRegistry.FORMAT_MARKDOWN, R.string.markdown, ".md", CONVERTER_MARKDOWN),
            new Format(FormatRegistry.FORMAT_PLAIN, R.string.plaintext, ".txt", CONVERTER_PLAINTEXT),
            new Format(FormatRegistry.FORMAT_UNKNOWN, R.string.none, "", null)
    );

    public static boolean isFileSupported(final File file, final boolean... textOnly) {
        final boolean textonly = textOnly != null && textOnly.length > 0 && textOnly[0];
        if (file != null) {
            for (final Format format : FORMATS) {
                if (textonly && format.converter instanceof PlaintextTextConverter) {
                    continue;
                }
                if (format.converter != null && format.converter.isFileOutOfThisFormat(file)) {
                    return true;
                }
            }
        }
        return false;
    }

    public interface TextFormatApplier {
        void applyTextFormat(int textFormatId);
    }

    public static FormatRegistry getFormat(int formatId, @NonNull final Context context, final Document document) {
        final FormatRegistry format = new FormatRegistry();
        final AppSettings appSettings = AppSettings.get(context);

        switch (formatId) {
            case FORMAT_PLAIN: {
                format._converter = CONVERTER_PLAINTEXT;
                format._highlighter = new PlaintextSyntaxHighlighter(appSettings, document.extension);
                // Should implement code action buttons for PlaintextActionButtons
                format._textActions = new PlaintextActionButtons(context, document);
                format._autoFormatInputFilter = new AutoTextFormatter(MarkdownReplacePatternGenerator.formatPatterns);
                format._autoFormatTextWatcher = new ListHandler(MarkdownReplacePatternGenerator.formatPatterns);
                break;
            }
            default:
            case FORMAT_MARKDOWN: {
                formatId = FORMAT_MARKDOWN;
                format._converter = CONVERTER_MARKDOWN;
                format._highlighter = new MarkdownSyntaxHighlighter(appSettings);
                format._textActions = new MarkdownActionButtons(context, document);
                format._autoFormatInputFilter = new AutoTextFormatter(MarkdownReplacePatternGenerator.formatPatterns);
                format._autoFormatTextWatcher = new ListHandler(MarkdownReplacePatternGenerator.formatPatterns);
                break;
            }
        }
        format._formatId = formatId;
        return format;
    }

    private ActionButtonBase _textActions;
    private SyntaxHighlighterBase _highlighter;
    private TextConverterBase _converter;
    private InputFilter _autoFormatInputFilter;
    private TextWatcher _autoFormatTextWatcher;
    private int _formatId;

    public ActionButtonBase getActions() {
        return _textActions;
    }

    public TextWatcher getAutoFormatTextWatcher() {
        return _autoFormatTextWatcher;
    }

    public InputFilter getAutoFormatInputFilter() {
        return _autoFormatInputFilter;
    }

    public SyntaxHighlighterBase getHighlighter() {
        return _highlighter;
    }

    public TextConverterBase getConverter() {
        return _converter;
    }

    public int getFormatId() {
        return _formatId;
    }

    public static boolean isExternalFile(final File file) {
        final String ext = GsFileUtils.getFilenameExtension(file).toLowerCase();
        return EXTERNAL_FILE_EXTENSIONS.contains(ext);
    }
}
