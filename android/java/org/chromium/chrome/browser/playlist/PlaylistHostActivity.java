/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.playlist;

import android.content.Intent;
import android.content.Context;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.brave.playlist.PlaylistViewModel;
import com.brave.playlist.enums.PlaylistOptionsEnum;
import com.brave.playlist.fragment.AllPlaylistFragment;
import com.brave.playlist.fragment.PlaylistFragment;
import com.brave.playlist.fragment.PlaylistPlayerFragment;
import com.brave.playlist.listener.PlaylistOptionsListener;
import com.brave.playlist.local_database.PlaylistRepository;
import com.brave.playlist.model.HlsContentProgressModel;
import com.brave.playlist.model.MoveOrCopyModel;
import com.brave.playlist.model.PlaylistItemModel;
import com.brave.playlist.model.PlaylistModel;
import com.brave.playlist.model.PlaylistOptionsModel;
import com.brave.playlist.playback_service.VideoPlaybackService;
import com.brave.playlist.util.ConstantUtils;
import com.brave.playlist.util.PlaylistUtils;
import com.brave.playlist.util.MediaItemUtil;
import com.google.common.util.concurrent.ListenableFuture;
import com.bumptech.glide.Glide;
import com.brave.playlist.view.bottomsheet.MoveOrCopyToPlaylistBottomSheet;

import org.chromium.base.BraveFeatureList;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.Log;
import org.chromium.base.supplier.OneshotSupplier;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveLandscapeHelper;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.init.ActivityProfileProvider;
import org.chromium.chrome.browser.init.AsyncInitializationActivity;
import org.chromium.chrome.browser.night_mode.NightModeStateProvider;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl.PlaylistServiceObserverImplDelegate;
import org.chromium.chrome.browser.playlist.hls_content.HlsService;
import org.chromium.chrome.browser.playlist.hls_content.HlsServiceImpl;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.profiles.ProfileProvider;
import org.chromium.chrome.browser.util.TabUtils;
import org.chromium.chrome.browser.toolbar.bottom.FairModeSwitcher;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.playlist.mojom.Playlist;
import org.chromium.playlist.mojom.PlaylistEvent;
import org.chromium.playlist.mojom.PlaylistItem;
import org.chromium.playlist.mojom.PlaylistService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class PlaylistHostActivity extends AsyncInitializationActivity
        implements ConnectionErrorHandler, PlaylistOptionsListener,
                   PlaylistServiceObserverImplDelegate {
    private static final String TAG = "PlaylistHostActivity";
    private PlaylistService mPlaylistService;
    private PlaylistViewModel mPlaylistViewModel;
    private PlaylistServiceObserverImpl mPlaylistServiceObserver;
    // Music Browser: our own explicit item ID, not an undocumented AAR intent extra.
    private static final String FAIR_RECENT_ID = "fair.recent_song_id";
    private ListenableFuture<MediaBrowser> mFairBrowser;
    private ImageView mFairArtwork;
    private Fragment mFairPlayerFragment;

    public static void openRecent(Context context, String id) {
        Intent intent = new Intent(context, PlaylistHostActivity.class);
        intent.putExtra(ConstantUtils.PLAYLIST_ID, ConstantUtils.DEFAULT_PLAYLIST);
        intent.putExtra(FAIR_RECENT_ID, id);
        context.startActivity(intent);
    }

    @Override
    public void onConnectionError(MojoException e) {
        if (mPlaylistService != null) {
            mPlaylistService.close();
            mPlaylistService = null;
        }
        if (ChromeFeatureList.isEnabled(BraveFeatureList.BRAVE_PLAYLIST)
                && ChromeSharedPreferences.getInstance()
                        .readBoolean(BravePreferenceKeys.PREF_ENABLE_PLAYLIST, true)) {
            initPlaylistService();
        }
    }

    private void initPlaylistService() {
        if (mPlaylistService != null) {
            mPlaylistService = null;
        }
        mPlaylistService =
                PlaylistServiceFactoryAndroid.getInstance()
                        .getPlaylistService(
                                getProfileProviderSupplier().get().getOriginalProfile(), this);
        addPlaylistObserver();
        FairSaveCount.get(this).initialize(mPlaylistService);
        FairUnlock.get(this);
    }

    private void addPlaylistObserver() {
        mPlaylistServiceObserver = new PlaylistServiceObserverImpl(this);
        mPlaylistService.addObserver(mPlaylistServiceObserver);
    }

    // Music Browser: scope always-dark appearance to Music, not the Browse activity.
    @Override
    protected NightModeStateProvider createNightModeStateProvider() {
        return new NightModeStateProvider() {
            @Override public boolean isInNightMode() { return true; }
            @Override public void addObserver(NightModeStateProvider.Observer observer) {}
            @Override public void removeObserver(NightModeStateProvider.Observer observer) {}
        };
    }

    @Override
    protected void initializeNightModeStateProvider() {
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    @Override
    protected void triggerLayoutInflation() {
        setContentView(R.layout.activity_playlist_host);
        // Music Browser: Browse returns to the same tab, without recreating the browser.
        FairModeSwitcher modeSwitcher = findViewById(R.id.fair_music_mode_switcher);
        modeSwitcher.setSelection(true, this::finish);
        installFairModeStrip();
        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fair_music_mode_strip),
                (view, insets) -> {
                    WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(view);
                    int bottom = (rootInsets != null ? rootInsets : insets)
                            .getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                    view.setPadding(view.getPaddingLeft(), padding, view.getPaddingRight(),
                            padding + bottom);
                    return insets;
                });
        ViewCompat.requestApplyInsets(findViewById(R.id.fair_music_mode_strip));
        onInitialLayoutInflationComplete();
    }

    // Music Browser: the AAR player replaces android.R.id.content, not our fragment container.
    // Keep the mode strip as a sibling above every fragment, and reserve its measured height.
    private void installFairModeStrip() {
        FrameLayout content = findViewById(android.R.id.content);
        View strip = findViewById(R.id.fair_music_mode_strip);
        ((ViewGroup) strip.getParent()).removeView(strip);
        content.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        Runnable reserveStrip = () -> {
            for (int i = 0; i < content.getChildCount(); i++) {
                View child = content.getChildAt(i);
                if (child == strip) continue;
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
                if (params.bottomMargin != strip.getHeight()) {
                    params.bottomMargin = strip.getHeight();
                    child.setLayoutParams(params);
                }
            }
            strip.bringToFront();
        };
        strip.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> reserveStrip.run());
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(FragmentManager manager, Fragment fragment,
                            View view, Bundle state) {
                        if (fragment instanceof PlaylistPlayerFragment) decorateFairPlayer(fragment, view);
                        updateFairLibraryLimit();
                        strip.post(reserveStrip);
                    }
                    @Override
                    public void onFragmentViewDestroyed(FragmentManager manager, Fragment fragment) {
                        if (fragment == mFairPlayerFragment) {
                            mFairArtwork = null;
                            mFairPlayerFragment = null;
                        }
                    }
                }, false);
        strip.post(reserveStrip);
    }

    @Override
    public void finishNativeInitialization() {
        super.finishNativeInitialization();
        if (ChromeFeatureList.isEnabled(BraveFeatureList.BRAVE_PLAYLIST)) {
            initPlaylistService();
        }
        FairSaveCount.get(this).changes().observe(this, value -> updateFairLibraryLimit());
        FairUnlock.get(this).changes().observe(this, value -> updateFairLibraryLimit());
        mPlaylistViewModel =
                new ViewModelProvider(PlaylistHostActivity.this).get(PlaylistViewModel.class);

        mPlaylistViewModel
                .getCreatePlaylistOption()
                .observe(
                        PlaylistHostActivity.this,
                        createPlaylistModel -> {
                            if (mPlaylistService == null) {
                                return;
                            }
                            Playlist playlist = new Playlist();
                            playlist.name = createPlaylistModel.getNewPlaylistId();
                            playlist.items = new PlaylistItem[0];
                            mPlaylistService.createPlaylist(
                                    playlist,
                                    createdPlaylist -> {
                                        if (createPlaylistModel.isMoveOrCopy()
                                                && PlaylistUtils.moveOrCopyModel != null) {
                                            MoveOrCopyModel tempMoveOrCopyModel =
                                                    PlaylistUtils.moveOrCopyModel;
                                            PlaylistUtils.moveOrCopyModel =
                                                    new MoveOrCopyModel(
                                                            tempMoveOrCopyModel
                                                                    .getPlaylistOptionsEnum(),
                                                            createdPlaylist.id,
                                                            tempMoveOrCopyModel.getPlaylistItems());
                                            mPlaylistViewModel.performMoveOrCopy(
                                                    PlaylistUtils.moveOrCopyModel);
                                        }
                                    });
                        });

        mPlaylistViewModel.getRenamePlaylistOption().observe(
                PlaylistHostActivity.this, renamePlaylistModel -> {
                    if (mPlaylistService == null) {
                        return;
                    }
                    mPlaylistService.renamePlaylist(renamePlaylistModel.getPlaylistId(),
                            renamePlaylistModel.getNewName(),
                            updatedPlaylist -> { loadPlaylist(updatedPlaylist.id); });
                });

        mPlaylistViewModel.getPlaylistToOpen().observe(
                PlaylistHostActivity.this, playlistId -> { showPlaylist(playlistId, true); });

        mPlaylistViewModel.getFetchPlaylistData().observe(PlaylistHostActivity.this, playlistId -> {
            if (mPlaylistService == null) {
                return;
            }
            if (playlistId.equals(ConstantUtils.ALL_PLAYLIST)) {
                loadAllPlaylists();
            } else {
                loadPlaylist(playlistId);
            }
        });

        mPlaylistViewModel.getReorderPlaylistItems().observe(
                PlaylistHostActivity.this, playlistItems -> {
                    if (mPlaylistService == null) {
                        return;
                    }
                    for (int i = 0; i < playlistItems.size(); i++) {
                        PlaylistItemModel playlistItemModel = playlistItems.get(i);
                        mPlaylistService.reorderItemFromPlaylist(playlistItemModel.getPlaylistId(),
                                playlistItemModel.getId(), (short) i, (result) -> {});
                    }
                });

        mPlaylistViewModel
                .getDeletePlaylistItems()
                .observe(
                        PlaylistHostActivity.this,
                        playlistItems -> {
                            if (mPlaylistService == null) {
                                return;
                            }
                            for (PlaylistItemModel playlistItem : playlistItems.getItems()) {
                                deleteHLSContent(playlistItem.getId());
                                mPlaylistService.removeItemFromPlaylist(
                                        playlistItems.getId(), playlistItem.getId());
                            }
                            if (playlistItems.getItems().size() > 0) {
                                loadPlaylist(playlistItems.getItems().get(0).getPlaylistId());
                            }
                        });

        mPlaylistViewModel
                .getMoveOrCopyItems()
                .observe(
                        PlaylistHostActivity.this,
                        moveOrCopyModel -> {
                            if (mPlaylistService == null) {
                                return;
                            }
                            if (moveOrCopyModel.getPlaylistOptionsEnum()
                                            == PlaylistOptionsEnum.MOVE_PLAYLIST_ITEM
                                    || moveOrCopyModel.getPlaylistOptionsEnum()
                                            == PlaylistOptionsEnum.MOVE_PLAYLIST_ITEMS) {
                                for (PlaylistItemModel playlistItem :
                                        moveOrCopyModel.getPlaylistItems()) {
                                    mPlaylistService.moveItem(
                                            playlistItem.getPlaylistId(),
                                            moveOrCopyModel.getToPlaylistId(),
                                            playlistItem.getId());
                                }
                            } else {
                                String[] playlistItemIds =
                                        new String[moveOrCopyModel.getPlaylistItems().size()];
                                for (int i = 0;
                                        i < moveOrCopyModel.getPlaylistItems().size();
                                        i++) {
                                    playlistItemIds[i] =
                                            moveOrCopyModel.getPlaylistItems().get(i).getId();
                                }
                                mPlaylistService.copyItemToPlaylist(
                                        playlistItemIds, moveOrCopyModel.getToPlaylistId());
                            }
                            if (moveOrCopyModel.getPlaylistItems().size() > 0) {
                                loadPlaylist(
                                        moveOrCopyModel.getPlaylistItems().get(0).getPlaylistId());
                            }
                        });

        mPlaylistViewModel
                .getPlaylistOption()
                .observe(
                        PlaylistHostActivity.this,
                        playlistOptionsModel -> {
                            if (mPlaylistService == null) {
                                return;
                            }
                            PlaylistOptionsEnum option = playlistOptionsModel.getOptionType();
                            if (option == PlaylistOptionsEnum.DELETE_PLAYLIST) {
                                if (playlistOptionsModel.getPlaylistModel() != null) {
                                    mPlaylistService.removePlaylist(
                                            playlistOptionsModel.getPlaylistModel().getId());
                                }
                            } else if (option == PlaylistOptionsEnum.MOVE_PLAYLIST_ITEMS) {
                                showMoveOrCopyPlaylistBottomSheet();
                            } else if (option == PlaylistOptionsEnum.COPY_PLAYLIST_ITEMS) {
                                showMoveOrCopyPlaylistBottomSheet();
                            }
                        });

        mPlaylistViewModel
                .getPlaylistItemOption()
                .observe(
                        PlaylistHostActivity.this,
                        playlistItemOption -> {
                            if (mPlaylistService == null) {
                                return;
                            }
                            PlaylistOptionsEnum option = playlistItemOption.getOptionType();
                            if (option == PlaylistOptionsEnum.MOVE_PLAYLIST_ITEM) {
                                showMoveOrCopyPlaylistBottomSheet();
                            } else if (option == PlaylistOptionsEnum.COPY_PLAYLIST_ITEM) {
                                showMoveOrCopyPlaylistBottomSheet();
                            } else if (option == PlaylistOptionsEnum.DELETE_ITEMS_OFFLINE_DATA) {
                                mPlaylistService.removeLocalDataForItem(
                                        playlistItemOption.getPlaylistItemModel().getId());
                                // Playlist item will be updated based on event
                            } else if (option == PlaylistOptionsEnum.OPEN_IN_NEW_TAB) {
                                openPlaylistInTab(
                                        false,
                                        playlistItemOption.getPlaylistItemModel().getPageSource());
                            } else if (option == PlaylistOptionsEnum.OPEN_IN_PRIVATE_TAB) {
                                openPlaylistInTab(
                                        true,
                                        playlistItemOption.getPlaylistItemModel().getPageSource());
                            } else if (option == PlaylistOptionsEnum.DELETE_PLAYLIST_ITEM) {
                                deleteHLSContent(playlistItemOption.getPlaylistItemModel().getId());
                                mPlaylistService.removeItemFromPlaylist(
                                        playlistItemOption.getPlaylistId(),
                                        playlistItemOption.getPlaylistItemModel().getId());
                                loadPlaylist(playlistItemOption.getPlaylistId());
                            }
                        });

        if (getIntent() != null) {
            String recent = getIntent().getStringExtra(FAIR_RECENT_ID);
            if (recent != null) {
                getIntent().removeExtra(FAIR_RECENT_ID);
                showRecentSong(recent);
                return;
            }
            if (!TextUtils.isEmpty(getIntent().getAction())
                    && getIntent().getAction().equals(ConstantUtils.PLAYLIST_ACTION)) {
                showPlaylistForPlayer();
            } else {
                String playlistId = getIntent().getStringExtra(ConstantUtils.PLAYLIST_ID);
                if (!TextUtils.isEmpty(playlistId)
                        && playlistId.equals(ConstantUtils.ALL_PLAYLIST)) {
                    showAllPlaylistsFragment();
                } else {
                    showPlaylist(playlistId, false);
                }
            }
        }
    }

    // Music Browser: lifetime count in the library header, not the current folder size.
    private void updateFairLibraryLimit() {
        View limit = findViewById(R.id.fair_library_limit_container);
        if (limit == null) return;
        FairSaveCount saves = FairSaveCount.get(this);
        FairUnlock unlock = FairUnlock.get(this);
        limit.setVisibility(!unlock.isUnlocked() && saves.count() >= (int) Math.ceil(saves.limit() * 0.9)
                ? View.VISIBLE : View.GONE);
        TextView value = findViewById(R.id.fair_library_limit);
        java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance();
        value.setText(getString(R.string.fair_free_songs,
                format.format(saves.count()), format.format(saves.limit())));
        findViewById(R.id.fair_library_unlock).setOnClickListener(view -> FairUnlockSheet.show(this));
        findViewById(R.id.fair_library_notice).setVisibility(
                unlock.hasUnlockNotice() ? View.VISIBLE : View.GONE);
    }

    // Music Browser: UI-only audio-first artwork; the AAR keeps all playback controls/engine.
    private ListenableFuture<MediaBrowser> fairBrowser() {
        if (mFairBrowser == null) {
            mFairBrowser = new MediaBrowser.Builder(this,
                    new SessionToken(this, new ComponentName(this, VideoPlaybackService.class)))
                    .buildAsync();
            ListenableFuture<MediaBrowser> future = mFairBrowser;
            future.addListener(() -> {
                if (isDestroyed()) return;
                try {
                    MediaBrowser browser = future.get();
                    browser.addListener(new Player.Listener() {
                        @Override public void onMediaItemTransition(MediaItem item, int reason) {
                            updateFairArtwork(item);
                        }
                    });
                    updateFairArtwork(browser.getCurrentMediaItem());
                } catch (ExecutionException failed) {
                    // Keep the flat note placeholder; the AAR can still connect independently.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, ContextCompat.getMainExecutor(this));
        }
        return mFairBrowser;
    }

    private void updateFairArtwork(MediaItem item) {
        if (mFairArtwork == null || isDestroyed()) return;
        Glide.with(this).load(item == null ? null : item.mediaMetadata.artworkUri)
                .centerCrop().dontAnimate().placeholder(R.drawable.fair_player_note)
                .error(R.drawable.fair_player_note).into(mFairArtwork);
    }

    private void decorateFairPlayer(Fragment fragment, View view) {
        mFairPlayerFragment = fragment;
        view.setBackgroundColor(getColor(R.color.fair_player_background));
        View toolbar = view.findViewById(R.id.playlistToolbar);
        if (toolbar != null) toolbar.setBackgroundColor(getColor(R.color.fair_player_background));
        applyFairPlayerText(view);
        int[] toolbarIcons = {R.id.ivBackToolbarPlaylist, R.id.ivOptionsToolbarPlaylist};
        for (int id : toolbarIcons) {
            ImageView icon = view.findViewById(id);
            if (icon != null) icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.fair_player_text)));
        }
        AspectRatioFrameLayout frame = view.findViewById(R.id.aspect_ratio_frame_layout);
        if (frame == null) return;
        int margin = Math.round(16 * getResources().getDisplayMetrics().density);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) frame.getLayoutParams();
        params.leftMargin = margin;
        params.rightMargin = margin;
        frame.setLayoutParams(params);
        frame.setAspectRatio(1f);
        frame.addOnLayoutChangeListener((v, left, top, right, bottom, ol, ot, or, ob) -> {
            if (frame.getWidth() > 0 && frame.getLayoutParams().height != frame.getWidth()) {
                ViewGroup.LayoutParams square = frame.getLayoutParams();
                square.height = frame.getWidth();
                frame.setLayoutParams(square);
                frame.setAspectRatio(1f);
            }
        });
        mFairArtwork = new ImageView(this);
        mFairArtwork.setScaleType(ImageView.ScaleType.CENTER);
        mFairArtwork.setBackgroundColor(getColor(R.color.fair_player_layer));
        mFairArtwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mFairArtwork.setImageResource(R.drawable.fair_player_note);
        frame.addView(mFairArtwork, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ListenableFuture<MediaBrowser> future = fairBrowser();
        future.addListener(() -> {
            if (isDestroyed()) return;
            try { updateFairArtwork(future.get().getCurrentMediaItem()); }
            catch (ExecutionException failed) { }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void applyFairPlayerText(View view) {
        if (view instanceof TextView) {
            int id = view.getId();
            boolean meta = id == R.id.tvMediaDuration || id == R.id.tvMediaFileSize
                    || id == R.id.tvMediaDownloadProgress || id == R.id.tvVideoTimeElapsed
                    || id == R.id.tvVideoTimeRemaining;
            ((TextView) view).setTextColor(getColor(meta
                    ? R.color.fair_player_secondary : R.color.fair_player_text));
        }
        if (view instanceof RecyclerView) {
            ((RecyclerView) view).addOnChildAttachStateChangeListener(
                    new RecyclerView.OnChildAttachStateChangeListener() {
                        @Override public void onChildViewAttachedToWindow(View child) { applyFairPlayerText(child); }
                        @Override public void onChildViewDetachedFromWindow(View child) { }
                    });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyFairPlayerText(group.getChildAt(i));
        }
    }

    // Music Browser: pinned AAR APIs confirmed with javap (SHA-256 1ccbd933…).
    // Await our MediaBrowser connection rather than racing the AAR fragment's private future.
    private void showRecentSong(String id) {
        if (mPlaylistService == null) return;
        mPlaylistService.getPlaylistItem(id, item -> {
            if (item == null || mPlaylistService == null) {
                showPlaylist(ConstantUtils.DEFAULT_PLAYLIST, false);
                return;
            }
            String parent = item.parents.length > 0 ? item.parents[0] : ConstantUtils.DEFAULT_PLAYLIST;
            mPlaylistService.getPlaylist(parent, playlist -> {
                if (playlist == null || isFinishing() || isDestroyed()) return;
                List<PlaylistItemModel> models = new ArrayList<>();
                List<MediaItem> media = new ArrayList<>();
                int selected = -1;
                for (PlaylistItem song : playlist.items) {
                    PlaylistItemModel model = new PlaylistItemModel(song.id, playlist.id, song.name,
                            song.pageSource.url, song.mediaPath.url, song.hlsMediaPath.url,
                            song.mediaSource.url, song.thumbnailPath.url, song.author, song.duration,
                            song.lastPlayedPosition, song.mediaFileBytes, song.cached, false);
                    // Include the tapped item while saving (MediaItemUtil uses its stream URL),
                    // plus available offline songs. Player row indices then match queue indices.
                    if (!song.id.equals(id) && !PlaylistUtils.isPlaylistItemCached(model)) continue;
                    if (song.id.equals(id)) selected = media.size();
                    models.add(model);
                    media.add(MediaItemUtil.buildMediaItem(model, playlist.id, playlist.name));
                }
                if (selected < 0) { showPlaylist(parent, false); return; }
                PlaylistModel queue = new PlaylistModel(playlist.id, playlist.name, models);
                int index = selected;
                ListenableFuture<MediaBrowser> browserFuture = fairBrowser();
                browserFuture.addListener(() -> {
                    if (isFinishing() || isDestroyed()
                            || getSupportFragmentManager().isStateSaved()) return;
                    try {
                        MediaBrowser browser = browserFuture.get();
                        browser.setMediaItems(media, index, 0);
                        browser.setShuffleModeEnabled(false);
                        browser.prepare();
                        browser.play();
                        getSupportFragmentManager().beginTransaction()
                                .replace(android.R.id.content, PlaylistPlayerFragment.newInstance(queue))
                                .addToBackStack(PlaylistFragment.class.getSimpleName()).commit();
                    } catch (ExecutionException failed) {
                        showPlaylist(parent, false);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, ContextCompat.getMainExecutor(this));
            });
        });
    }

    private void showPlaylist(String playlistId, boolean shouldFallback) {
        if (mPlaylistService == null) {
            return;
        }
        loadPlaylist(playlistId);
        PlaylistFragment playlistFragment = new PlaylistFragment();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction().replace(
                R.id.fragment_container_view_tag, playlistFragment);
        if (shouldFallback) {
            transaction.addToBackStack(AllPlaylistFragment.class.getSimpleName());
        }
        transaction.commit();
    }

    private void showPlaylistForPlayer() {
        if (mPlaylistService == null
                && TextUtils.isEmpty(VideoPlaybackService.Companion.getCurrentPlaylistId())) {
            return;
        }
        loadPlaylist(VideoPlaybackService.Companion.getCurrentPlaylistId());
        PlaylistFragment playlistFragment = new PlaylistFragment();
        Bundle fragmentBundle = new Bundle();
        fragmentBundle.putBoolean(ConstantUtils.SHOULD_OPEN_PLAYER, true);
        playlistFragment.setArguments(fragmentBundle);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction().replace(
                R.id.fragment_container_view_tag, playlistFragment);
        transaction.commit();
    }

    private void loadPlaylist(String playlistId) {
        if (mPlaylistService == null) {
            return;
        }
        mPlaylistService.getPlaylist(
                playlistId,
                playlist -> {
                    if (playlist == null) {
                        Log.d(TAG, "loadPlaylist is null from service");
                        return;
                    }
                    List<PlaylistItemModel> playlistItems = new ArrayList<>();
                    for (PlaylistItem playlistItem : playlist.items) {
                        PlaylistItemModel playlistItemModel =
                                new PlaylistItemModel(
                                        playlistItem.id,
                                        playlist.id,
                                        playlistItem.name,
                                        playlistItem.pageSource.url,
                                        playlistItem.mediaPath.url,
                                        playlistItem.hlsMediaPath.url,
                                        playlistItem.mediaSource.url,
                                        playlistItem.thumbnailPath.url,
                                        playlistItem.author,
                                        playlistItem.duration,
                                        playlistItem.lastPlayedPosition,
                                        playlistItem.mediaFileBytes,
                                        playlistItem.cached,
                                        false);
                        playlistItems.add(playlistItemModel);
                    }
                    PlaylistModel playlistModel =
                            new PlaylistModel(playlist.id, playlist.name, playlistItems);
                    if (mPlaylistViewModel != null) {
                        mPlaylistViewModel.setPlaylistData(playlistModel);
                    }
                });
    }

    private void loadAllPlaylists() {
        if (mPlaylistService == null) {
            return;
        }
        mPlaylistService.getAllPlaylists(
                playlists -> {
                    List<PlaylistModel> allPlaylists = new ArrayList<>();
                    for (Playlist playlist : playlists) {
                        List<PlaylistItemModel> playlistItems = new ArrayList<>();
                        for (PlaylistItem playlistItem : playlist.items) {
                            PlaylistItemModel playlistItemModel =
                                    new PlaylistItemModel(
                                            playlistItem.id,
                                            playlist.id,
                                            playlistItem.name,
                                            playlistItem.pageSource.url,
                                            playlistItem.mediaPath.url,
                                            playlistItem.hlsMediaPath.url,
                                            playlistItem.mediaSource.url,
                                            playlistItem.thumbnailPath.url,
                                            playlistItem.author,
                                            playlistItem.duration,
                                            playlistItem.lastPlayedPosition,
                                            playlistItem.mediaFileBytes,
                                            playlistItem.cached,
                                            false);
                            playlistItems.add(playlistItemModel);
                        }
                        PlaylistModel playlistModel =
                                new PlaylistModel(playlist.id, playlist.name, playlistItems);
                        allPlaylists.add(playlistModel);
                    }
                    if (mPlaylistViewModel != null) {
                        mPlaylistViewModel.setAllPlaylistData(allPlaylists);
                    }
                });
    }

    private void showAllPlaylistsFragment() {
        AllPlaylistFragment allPlaylistFragment = new AllPlaylistFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container_view_tag, allPlaylistFragment)
                .commit();
    }

    private void showMoveOrCopyPlaylistBottomSheet() {
        loadAllPlaylists();
        new MoveOrCopyToPlaylistBottomSheet().show(getSupportFragmentManager(), null);
    }

    @Override
    public void onPlaylistOptionClicked(PlaylistOptionsModel playlistOptionsModel) {
        if (PlaylistOptionsEnum.DELETE_PLAYLIST == playlistOptionsModel.getOptionType()
                && mPlaylistService != null
                && playlistOptionsModel.getPlaylistModel() != null) {
            mPlaylistService.removePlaylist(playlistOptionsModel.getPlaylistModel().getId());
            finish();
        }
    }

    private void openPlaylistInTab(boolean isIncognito, String url) {
        finish();
        TabUtils.openUrlInNewTab(isIncognito, url);
    }

    private void deleteHLSContent(String playlistItemId) {
        PostTask.postTask(
                TaskTraits.BEST_EFFORT_MAY_BLOCK,
                () -> {
                    PlaylistRepository playlistRepository =
                            new PlaylistRepository(PlaylistHostActivity.this);
                    if (playlistRepository != null
                            && playlistRepository.isHlsContentQueueModelExists(playlistItemId)) {
                        playlistRepository.deleteHlsContentQueueModel(playlistItemId);
                    }
                    if (HlsServiceImpl.currentDownloadingPlaylistItemId.equals(playlistItemId)) {
                        HlsServiceImpl.currentDownloadingPlaylistItemId = "";
                        mPlaylistService.cancelQuery(playlistItemId);
                        stopService(new Intent(PlaylistHostActivity.this, HlsService.class));
                        PlaylistUtils.checkAndStartHlsDownload(PlaylistHostActivity.this);
                    }
                });
    }

    @Override
    public void onItemCached(PlaylistItem playlistItem) {
        loadPlaylist(ConstantUtils.DEFAULT_PLAYLIST);
    }

    @Override
    public void onItemUpdated(PlaylistItem playlistItem) {
        loadPlaylist(ConstantUtils.DEFAULT_PLAYLIST);
    }

    @Override
    public void onPlaylistUpdated(Playlist playlist) {
        // Used only for reorder items
        loadPlaylist(playlist.id);
    }

    @Override
    public void onEvent(int eventType, String id) {
        if (eventType == PlaylistEvent.LIST_CREATED || eventType == PlaylistEvent.LIST_REMOVED) {
            loadAllPlaylists();
        }
    }

    @Override
    public void onMediaFileDownloadProgressed(String id, long totalBytes, long receivedBytes,
            byte percentComplete, String timeRemaining) {
        if (mPlaylistViewModel != null) {
            mPlaylistViewModel.updateDownloadProgress(
                    new HlsContentProgressModel(
                            id, totalBytes, receivedBytes, "" + percentComplete));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) BraveLandscapeHelper.applyLandscapeWindowSizing(this);
    }

    @Override
    public void onDestroy() {
        if (mFairBrowser != null) MediaBrowser.releaseFuture(mFairBrowser);
        FairUnlock.get(this).clearUnlockNotice();
        if (mPlaylistService != null) {
            mPlaylistService.close();
            mPlaylistService = null;
        }
        if (mPlaylistServiceObserver != null) {
            mPlaylistServiceObserver.close();
            mPlaylistServiceObserver.destroy();
            mPlaylistServiceObserver = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean shouldStartGpuProcess() {
        return true;
    }

    @Override
    protected OneshotSupplier<ProfileProvider> createProfileProvider() {
        return new ActivityProfileProvider(getLifecycleDispatcher());
    }
}
