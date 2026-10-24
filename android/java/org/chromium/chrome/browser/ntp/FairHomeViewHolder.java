/* Copyright 2026 The Music Browser Authors. MPL-2.0. */
package org.chromium.chrome.browser.ntp;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.brave.playlist.util.ConstantUtils;
import com.bumptech.glide.RequestManager;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.playlist.FairRecentSongs;
import org.chromium.chrome.browser.playlist.PlaylistHostActivity;
import org.chromium.chrome.browser.playlist.PlaylistServiceFactoryAndroid;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl.PlaylistServiceObserverImplDelegate;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.playlist.mojom.PlaylistEvent;
import org.chromium.playlist.mojom.PlaylistItem;
import org.chromium.playlist.mojom.PlaylistService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Music Browser: up to five genuinely recent saves, never pref dictionary order. */
public final class FairHomeViewHolder extends RecyclerView.ViewHolder
        implements ConnectionErrorHandler, PlaylistServiceObserverImplDelegate {
    private final Activity mActivity;
    private final RequestManager mGlide;
    private final LinearLayout mRecent;
    private final List<PlaylistItem> mItems = new ArrayList<>();
    private final Map<String, Integer> mProgress = new HashMap<>();
    private final Map<String, TextView> mMetadataViews = new HashMap<>();
    private final Set<String> mFailed = new HashSet<>();
    private PlaylistService mService;
    private PlaylistServiceObserverImpl mObserver;
    private int mGeneration;

    public FairHomeViewHolder(Activity activity, RequestManager glide) {
        super(new LinearLayout(activity));
        mActivity = activity;
        mGlide = glide;
        LinearLayout root = (LinearLayout) itemView;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        root.setBackgroundColor(activity.getColor(R.color.page_background));
        root.addView(text(activity.getString(R.string.fair_app_name), 12, true));
        TextView title = text(activity.getString(R.string.fair_home_title), 32, false);
        title.setPadding(0, dp(8), 0, dp(16));
        title.setAccessibilityHeading(true);
        root.addView(title);
        root.addView(text(activity.getString(R.string.fair_home_intro), 16, true));
        mRecent = new LinearLayout(activity);
        mRecent.setOrientation(LinearLayout.VERTICAL);
        mRecent.setPadding(0, dp(24), 0, 0);
        root.addView(mRecent);
        mRecent.setVisibility(View.GONE);
    }

    public void attach() {
        if (mService != null) { refresh(); return; }
        mService = PlaylistServiceFactoryAndroid.getInstance().getPlaylistService(
                ProfileManager.getLastUsedRegularProfile(), this);
        mObserver = new PlaylistServiceObserverImpl(this);
        mService.addObserver(mObserver);
        refresh();
    }

    public void detach() {
        mGeneration++;
        if (mService != null) { mService.close(); mService = null; }
        if (mObserver != null) { mObserver.close(); mObserver.destroy(); mObserver = null; }
    }

    public void refresh() {
        if (mService == null) return;
        int generation = ++mGeneration;
        mService.getAllPlaylistItems(items -> {
            if (mService == null || generation != mGeneration) return;
            Map<String, PlaylistItem> live = new HashMap<>();
            for (PlaylistItem item : items) live.put(item.id, item);
            mItems.clear();
            for (String id : FairRecentSongs.read()) {
                PlaylistItem item = live.get(id);
                if (item != null) mItems.add(item);
                if (mItems.size() == 5) break;
            }
            renderRecent();
        });
    }

    private void renderRecent() {
        mMetadataViews.clear();
        mRecent.removeAllViews();
        mRecent.setVisibility(mItems.isEmpty() ? View.GONE : View.VISIBLE);
        if (mItems.isEmpty()) return;
        TextView heading = text(mActivity.getString(R.string.fair_recent_saves), 14, false);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, 0, 0, dp(8));
        mRecent.addView(heading);
        for (PlaylistItem item : mItems) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, dp(8), 0, dp(8));
            ImageView artwork = new ImageView(mActivity);
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setImageResource(R.drawable.fair_music_note);
            GradientDrawable border = new GradientDrawable();
            border.setColor(android.graphics.Color.TRANSPARENT);
            border.setStroke(dp(1), mActivity.getColor(R.color.divider_subtle));
            artwork.setForeground(border);
            artwork.setBackgroundColor(mActivity.getColor(R.color.container_highlight));
            artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(artwork, new LinearLayout.LayoutParams(dp(48), dp(48)));
            if (!item.thumbnailPath.url.isEmpty()) {
                mGlide.load(item.thumbnailPath.url).centerCrop()
                        .placeholder(R.drawable.fair_music_note).error(R.drawable.fair_music_note)
                        .into(artwork);
            }
            LinearLayout labels = new LinearLayout(mActivity);
            labels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            labelParams.setMarginStart(dp(16));
            row.addView(labels, labelParams);
            TextView name = text(item.name, 16, false);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name);
            String metadata = metadata(item);
            TextView meta = text(metadata, 14, true);
            meta.setTypeface(Typeface.MONOSPACE);
            labels.addView(meta);
            mMetadataViews.put(item.id, meta);
            row.setFocusable(true);
            row.setContentDescription(item.name + ", " + metadata);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            meta.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.setOnClickListener(view -> PlaylistHostActivity.openRecent(mActivity, item.id));
            mRecent.addView(row);
            View rule = new View(mActivity);
            rule.setBackgroundColor(mActivity.getColor(R.color.divider_subtle));
            mRecent.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        Button all = new Button(mActivity);
        all.setText(R.string.fair_show_all_music);
        all.setTextSize(16);
        all.setAllCaps(false);
        all.setMinHeight(dp(48));
        all.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        all.setPadding(0, dp(8), 0, dp(8));
        all.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        all.setTextColor(mActivity.getColor(R.color.text_interactive));
        all.setOnClickListener(view -> {
            if (mActivity instanceof BraveActivity) {
                ((BraveActivity) mActivity).openPlaylistActivity(mActivity, ConstantUtils.DEFAULT_PLAYLIST);
            }
        });
        mRecent.addView(all);
    }

    private String metadata(PlaylistItem item) {
        String duration = "";
        try {
            long seconds = Math.max(0, Long.parseLong(item.duration) / 1_000_000);
            if (seconds > 0) duration = String.format(java.util.Locale.getDefault(),
                    "%d:%02d · ", seconds / 60, seconds % 60);
        } catch (NumberFormatException invalid) { }
        if (mFailed.contains(item.id)) return duration + mActivity.getString(R.string.fair_not_saved);
        if (item.cached) return duration + Formatter.formatFileSize(mActivity, item.mediaFileBytes);
        Integer progress = mProgress.get(item.id);
        return duration + (progress == null ? mActivity.getString(R.string.fair_saving)
                : mActivity.getString(R.string.fair_saving_percent, progress));
    }

    private TextView text(String value, int size, boolean secondary) {
        TextView text = new TextView(mActivity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(mActivity.getColor(secondary ? R.color.text_secondary : R.color.text_primary));
        text.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return text;
    }

    private int dp(int value) { return Math.round(value * mActivity.getResources().getDisplayMetrics().density); }
    @Override public void onConnectionError(MojoException error) { detach(); }
    @Override public void onItemCreated(PlaylistItem item) { refresh(); }
    @Override public void onItemCached(PlaylistItem item) { mFailed.remove(item.id); refresh(); }
    @Override public void onItemUpdated(PlaylistItem item) { refresh(); }
    @Override public void onItemRemovedFromList(String playlist, String item) { refresh(); }
    @Override public void onEvent(int event, String id) {
        if (event == PlaylistEvent.ITEM_ABORTED) mFailed.add(id);
        refresh();
    }
    @Override public void onMediaFileDownloadProgressed(String id, long totalBytes, long receivedBytes,
            byte percentComplete, String remaining) {
        mProgress.put(id, Math.min(100, Math.max(0, (int) percentComplete)));
        TextView meta = mMetadataViews.get(id);
        if (meta != null) {
            for (PlaylistItem item : mItems) {
                if (item.id.equals(id)) {
                    String value = metadata(item);
                    meta.setText(value);
                    ((View) meta.getParent().getParent()).setContentDescription(item.name + ", " + value);
                    break;
                }
            }
        }
    }
}
