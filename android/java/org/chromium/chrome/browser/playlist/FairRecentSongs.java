/* Copyright 2026 The Music Browser Authors. MPL-2.0. */
package org.chromium.chrome.browser.playlist;

import org.json.JSONArray;
import org.json.JSONException;

import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.playlist.mojom.PlaylistItem;

import java.util.ArrayList;
import java.util.List;

/** Music Browser: only successful new saves establish recency, never library dictionary order. */
public final class FairRecentSongs {
    private static final String KEY = "fair.recent_song_ids";

    public static List<String> read() {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray stored = new JSONArray(
                    ChromeSharedPreferences.getInstance().readString(KEY, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                String id = stored.getString(i);
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        } catch (JSONException invalid) {
            // No fabricated dates for a damaged index or pre-Fair items.
        }
        return ids;
    }

    public static void record(PlaylistItem[] created) {
        List<String> ids = read();
        for (PlaylistItem item : created) {
            ids.remove(item.id);
            ids.add(0, item.id);
        }
        // The UI resolves live IDs and displays at most five. Retain older IDs so
        // deleting a recent song can reveal the next genuinely dated save.
        ChromeSharedPreferences.getInstance().writeString(KEY, new JSONArray(ids).toString());
    }

    private FairRecentSongs() {}
}
