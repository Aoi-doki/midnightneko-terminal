package com.termux.styling;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.theme.NightMode;
import com.termux.styling.StyleAsset.Kind;

import java.util.List;

/**
 * Colour scheme and font picker, merged from termux-styling.
 *
 * <p>Two changes from upstream. It is a Java rewrite, so the build no longer needs the Kotlin
 * plugin; and it is an in-app screen reached from the terminal's long-press menu and from
 * Settings, rather than a separate app with its own launcher icon showing a bare two-button
 * dialog.
 *
 * <p>Long-pressing an entry still shows its licence, exactly as upstream did.
 */
public class TermuxStyleActivity extends AppCompatActivity {

    private static final String LOG_TAG = "TermuxStyleActivity";

    /**
     * Optional {@code String} extra naming a {@link Kind} to open the picker for straight away,
     * so Settings can link directly at "Colour scheme" or "Font".
     */
    public static final String EXTRA_OPEN_PICKER = "com.termux.styling.open_picker";

    /** Set once a picker has been auto-opened, so it does not reopen on every rotation. */
    private static final String STATE_PICKER_OPENED = "picker_opened";

    private boolean mPickerOpened;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux_style);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setToolbarTitle(this, com.termux.shared.R.id.toolbar,
            getString(R.string.mayonaka_style_activity_title), 0);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        findViewById(R.id.row_colors).setOnClickListener(v -> showPicker(Kind.COLORS));
        findViewById(R.id.row_font).setOnClickListener(v -> showPicker(Kind.FONT));

        mPickerOpened = savedInstanceState != null && savedInstanceState.getBoolean(STATE_PICKER_OPENED, false);

        if (!mPickerOpened) {
            String requested = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_OPEN_PICKER);
            if (requested != null) {
                mPickerOpened = true;
                try {
                    showPicker(Kind.valueOf(requested));
                } catch (IllegalArgumentException e) {
                    Logger.logError(LOG_TAG, "Unknown picker kind \"" + requested + "\"");
                }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PICKER_OPENED, mPickerOpened);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSummaries();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void updateSummaries() {
        ((TextView) findViewById(R.id.summary_colors))
            .setText(StyleAsset.currentDisplayName(this, Kind.COLORS));
        ((TextView) findViewById(R.id.summary_font))
            .setText(StyleAsset.currentDisplayName(this, Kind.FONT));
    }

    private void showPicker(@NonNull Kind kind) {
        List<StyleAsset> entries = StyleAsset.list(this, kind);

        ArrayAdapter<StyleAsset> adapter =
            new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, entries);

        int checked = StyleAsset.indexOfCurrent(this, kind, entries);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(kind == Kind.COLORS ? R.string.color_prompt : R.string.font_prompt)
            .setSingleChoiceItems(adapter, checked, (d, which) -> {
                apply(entries.get(which), kind);
                d.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();

        // Long-press shows the entry's licence, as upstream did.
        dialog.setOnShowListener(d -> dialog.getListView().setOnItemLongClickListener(
            (parent, view, position, id) -> {
                showLicense(entries.get(position), kind);
                return true;
            }));

        dialog.show();

        // Scroll to whatever is currently applied -- with 114 schemes, starting at the top is
        // not helpful.
        dialog.getListView().setSelection(Math.max(0, checked - 2));
    }

    private void apply(@NonNull StyleAsset asset, @NonNull Kind kind) {
        String error = asset.install(this, kind);
        if (error != null) {
            Toast.makeText(this, getString(R.string.writing_failed) + error, Toast.LENGTH_LONG).show();
            return;
        }
        updateSummaries();
        Toast.makeText(this, getString(R.string.mayonaka_style_applied, asset.displayName),
            Toast.LENGTH_SHORT).show();
    }

    private void showLicense(@NonNull StyleAsset asset, @NonNull Kind kind) {
        String license = asset.readLicense(this, kind);
        if (license == null) {
            Toast.makeText(this, R.string.mayonaka_style_no_license, Toast.LENGTH_SHORT).show();
            return;
        }

        SpannableString text = new SpannableString(license);
        Linkify.addLinks(text, Linkify.ALL);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(asset.displayName)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show();

        View message = dialog.findViewById(android.R.id.message);
        if (message instanceof TextView)
            ((TextView) message).setMovementMethod(LinkMovementMethod.getInstance());
    }

    /** Open this screen, optionally straight into one of the pickers. */
    public static void start(@NonNull Context context, @Nullable Kind openPicker) {
        Intent intent = new Intent(context, TermuxStyleActivity.class);
        if (openPicker != null) intent.putExtra(EXTRA_OPEN_PICKER, openPicker.name());
        context.startActivity(intent);
    }
}
