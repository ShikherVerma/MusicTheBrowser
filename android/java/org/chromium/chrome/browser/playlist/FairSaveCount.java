/* Copyright 2026 The Music Browser Authors. MPL-2.0. */
package org.chromium.chrome.browser.playlist;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.android.gms.auth.blockstore.Blockstore;
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest;
import com.google.android.gms.auth.blockstore.StoreBytesData;

import org.chromium.base.CommandLine;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.playlist.mojom.PlaylistService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Music Browser: lifetime songs, reconciled with device-only Block Store before any save. */
public final class FairSaveCount {
    private static final String LOCAL_KEY = "fair.saved_song_count";
    private static final String DEVICE_KEY = "savedSongCount";
    private static FairSaveCount sInstance;
    private final Context mContext;
    private final MutableLiveData<Integer> mCount;
    private final Set<String> mPending = new HashSet<>();
    private final List<Runnable> mWaiting = new ArrayList<>();
    private boolean mStarted;
    private boolean mFloorRead;
    private boolean mDeviceRead;
    private boolean mCanWriteDevice;
    private boolean mReady;
    private boolean mWritingDevice;

    private FairSaveCount(Context context) {
        mContext = context.getApplicationContext();
        mCount = new MutableLiveData<>(Math.max(0,
                ChromeSharedPreferences.getInstance().readInt(LOCAL_KEY, 0)));
    }

    public static FairSaveCount get(Context context) {
        if (sInstance == null) sInstance = new FairSaveCount(context);
        return sInstance;
    }

    public int count() { return mCount.getValue(); }
    public LiveData<Integer> changes() { return mCount; }

    public int limit() {
        if ((mContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && CommandLine.getInstance().hasSwitch("fair-free-song-limit")) {
            try {
                int value = Integer.parseInt(
                        CommandLine.getInstance().getSwitchValue("fair-free-song-limit"));
                if (value > 0) return value;
            } catch (NumberFormatException ignored) { }
        }
        return 1000;
    }

    public void initialize(PlaylistService service) {
        if (mStarted) return;
        mStarted = true;
        service.getAllPlaylistItems(items -> {
            Set<String> ids = new HashSet<>();
            for (var item : items) ids.add(item.id);
            setCount(Math.max(count(), ids.size()));
            mFloorRead = true;
            becomeReady();
        });
        try {
            Blockstore.getClient(mContext).retrieveBytes(new RetrieveBytesRequest.Builder()
                    .setKeys(Collections.singletonList(DEVICE_KEY)).build())
                    .addOnSuccessListener(response -> {
                        var data = response.getBlockstoreDataMap().get(DEVICE_KEY);
                        if (data != null) {
                            try {
                                int stored = Integer.parseInt(
                                        new String(data.getBytes(), StandardCharsets.UTF_8));
                                if (stored < 0) throw new NumberFormatException();
                                setCount(Math.max(count(), stored));
                            } catch (NumberFormatException invalid) {
                                // Do not overwrite an unreadable device copy.
                                mDeviceRead = true;
                                becomeReady();
                                return;
                            }
                        }
                        mCanWriteDevice = true;
                        mDeviceRead = true;
                        becomeReady();
                    })
                    .addOnFailureListener(error -> {
                        mDeviceRead = true;
                        becomeReady();
                    });
        } catch (RuntimeException unavailable) {
            mDeviceRead = true;
            becomeReady();
        }
    }

    private void becomeReady() {
        if (mReady || !mFloorRead || !mDeviceRead) return;
        mReady = true;
        persist();
        List<Runnable> waiting = new ArrayList<>(mWaiting);
        mWaiting.clear();
        for (Runnable callback : waiting) callback.run();
    }

    public void whenReady(Runnable callback) {
        if (mReady) callback.run();
        else mWaiting.add(callback);
    }

    public boolean isPending(String source) { return mPending.contains(source); }

    public boolean reserve(String source, boolean unlocked) {
        if (!mReady || mPending.contains(source)
                || (!unlocked && count() + mPending.size() >= limit())) return false;
        mPending.add(source);
        return true;
    }

    public void finish(List<String> sources, int created) {
        int reserved = 0;
        for (String source : sources) if (mPending.remove(source)) reserved++;
        setCount(count() + Math.min(reserved, Math.max(0, created)));
        persist();
    }

    private void setCount(int count) {
        mCount.setValue(count);
        ChromeSharedPreferences.getInstance().writeInt(LOCAL_KEY, count);
    }

    private void persist() {
        ChromeSharedPreferences.getInstance().writeInt(LOCAL_KEY, count());
        if (!mReady || !mCanWriteDevice || mWritingDevice) return;
        int writing = count();
        mWritingDevice = true;
        // No setShouldBackupToCloud: the count stays on the device, never in cloud backup.
        try {
            Blockstore.getClient(mContext).storeBytes(new StoreBytesData.Builder()
                    .setKey(DEVICE_KEY)
                    .setBytes(Integer.toString(writing).getBytes(StandardCharsets.UTF_8))
                    .build()).addOnCompleteListener(result -> {
                        mWritingDevice = false;
                        // Serialize writes: an older count must never land after a newer one.
                        if (result.isSuccessful() && count() > writing) persist();
                    });
        } catch (RuntimeException unavailable) {
            mWritingDevice = false;
            // The local count still advances if Google Play services disappears.
        }
    }
}
