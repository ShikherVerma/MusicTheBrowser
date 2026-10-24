/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.bottom;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.LinearLayout;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;

/** Music Browser: one square, fixed-palette mode switch in both activities. */
@NullMarked
public final class FairModeSwitcher extends LinearLayout {
    private final Button mBrowse;
    private final Button mMusic;
    private boolean mMusicSelected;
    private Runnable mOnSwitch = () -> {};

    public FairModeSwitcher(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setMinimumWidth(dp(192));
        LayoutInflater.from(context).inflate(R.layout.fair_mode_switcher, this, true);
        mBrowse = findViewById(R.id.fair_mode_browse);
        mMusic = findViewById(R.id.fair_mode_music);
        mBrowse.setOnClickListener(v -> { if (mMusicSelected) mOnSwitch.run(); });
        mMusic.setOnClickListener(v -> { if (!mMusicSelected) mOnSwitch.run(); });
        updateColors();
    }

    public void setSelection(boolean musicSelected, Runnable onSwitch) {
        mMusicSelected = musicSelected;
        mOnSwitch = onSwitch;
        updateColors();
    }

    @Override
    protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateColors();
    }

    private void updateColors() {
        int inverse = getContext().getColor(R.color.text_primary);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(1), inverse);
        setForeground(border);
        setBackgroundColor(getContext().getColor(R.color.page_background));
        updateButton(mBrowse, !mMusicSelected, inverse, 1);
        updateButton(mMusic, mMusicSelected, inverse, 2);
    }

    private void updateButton(Button button, boolean selected, int inverse, int position) {
        button.setSelected(selected);
        button.setContentDescription(getContext().getString(
                R.string.fair_mode_accessibility, button.getText(), position));
        button.setTextColor(getContext().getColor(
                selected ? R.color.schemes_inverse_on_surface : R.color.text_secondary));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(selected ? inverse : Color.TRANSPARENT);
        // The drawable is a solid rectangle, not a gradient. Retain native touch feedback.
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(
                        31, Color.red(inverse), Color.green(inverse), Color.blue(inverse))),
                fill, null));
        button.setBackgroundTintList(null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
