/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_BROWSER_UI_BOOKMARK_BOOKMARK_HELPER_H_
#define BRAVE_BROWSER_UI_BOOKMARK_BOOKMARK_HELPER_H_

class PrefService;

namespace brave {

// Show bookmarks a drop down choice of three options:
// - Always (default)
// - Never
// - Only on the new tab page
enum class BookmarkBarState { kAlways, kNever, kNtp };

BookmarkBarState GetBookmarkBarState(PrefService* prefs);
void SetBookmarkState(BookmarkBarState state, PrefService* prefs);

// Keeps kBookmarkBarVisibilityState in sync with the legacy kShowBookmarkBar
// and kAlwaysShowBookmarkBarOnNTP prefs when they're changed directly (e.g.
// from brave://settings) instead of through SetBookmarkState().
void SyncBookmarkBarVisibilityState(PrefService* prefs);

}  // namespace brave

#endif  // BRAVE_BROWSER_UI_BOOKMARK_BOOKMARK_HELPER_H_
