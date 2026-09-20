/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/ui/bookmark/bookmark_helper.h"

#include "base/feature_list.h"
#include "base/notreached.h"
#include "brave/components/constants/pref_names.h"
#include "components/bookmarks/common/bookmark_bar_visibility_state.h"
#include "components/bookmarks/common/bookmark_pref_names.h"
#include "components/prefs/pref_service.h"
#include "components/search/ntp_features.h"

namespace brave {

BookmarkBarState GetBookmarkBarState(PrefService* prefs) {
  // With the NTP Simplification feature, kBookmarkBarVisibilityState is the
  // source of truth for BookmarkBarController's visibility computation, so
  // it must be consulted instead of the legacy kShowBookmarkBar pref.
  if (base::FeatureList::IsEnabled(
          ntp_features::kNtpSimplificationBookmarkBar)) {
    switch (static_cast<bookmarks::BookmarkBarVisibilityState>(
        prefs->GetInteger(bookmarks::prefs::kBookmarkBarVisibilityState))) {
      case bookmarks::BookmarkBarVisibilityState::kAlwaysShow:
        return BookmarkBarState::kAlways;
      case bookmarks::BookmarkBarVisibilityState::kOnlyShowOnNtp:
        return BookmarkBarState::kNtp;
      case bookmarks::BookmarkBarVisibilityState::kAlwaysHide:
        return BookmarkBarState::kNever;
    }
    NOTREACHED();
  }

  // kShowBookmarkBar has higher priority and the bookmark bar is shown always.
  if (prefs->GetBoolean(bookmarks::prefs::kShowBookmarkBar))
    return BookmarkBarState::kAlways;
  // kShowBookmarkBar is false, kAlwaysShowBookmarkBarOnNTP is true
  // -> the bookmark bar is shown only for NTP.
  if (prefs->GetBoolean(bookmarks::prefs::kAlwaysShowBookmarkBarOnNTP)) {
    return BookmarkBarState::kNtp;
  }
  // NEVER show the bookmark bar.
  return BookmarkBarState::kNever;
}

void SetBookmarkState(BookmarkBarState state, PrefService* prefs) {
  if (state == BookmarkBarState::kAlways) {
    prefs->SetBoolean(bookmarks::prefs::kShowBookmarkBar, true);
    prefs->SetBoolean(bookmarks::prefs::kAlwaysShowBookmarkBarOnNTP, false);
  } else if (state == BookmarkBarState::kNtp) {
    prefs->SetBoolean(bookmarks::prefs::kShowBookmarkBar, false);
    prefs->SetBoolean(bookmarks::prefs::kAlwaysShowBookmarkBarOnNTP, true);
  } else {
    prefs->SetBoolean(bookmarks::prefs::kShowBookmarkBar, false);
    prefs->SetBoolean(bookmarks::prefs::kAlwaysShowBookmarkBarOnNTP, false);
  }

  // BookmarkBarController::ShouldShowBookmarkBar() consults
  // kBookmarkBarVisibilityState instead of kShowBookmarkBar when the NTP
  // Simplification feature is enabled, so it must be kept in sync too.
  if (base::FeatureList::IsEnabled(
          ntp_features::kNtpSimplificationBookmarkBar)) {
    auto visibility_state = bookmarks::BookmarkBarVisibilityState::kAlwaysHide;
    if (state == BookmarkBarState::kAlways) {
      visibility_state = bookmarks::BookmarkBarVisibilityState::kAlwaysShow;
    } else if (state == BookmarkBarState::kNtp) {
      visibility_state = bookmarks::BookmarkBarVisibilityState::kOnlyShowOnNtp;
    }
    prefs->SetInteger(bookmarks::prefs::kBookmarkBarVisibilityState,
                      static_cast<int>(visibility_state));
  }
}

void SyncBookmarkBarVisibilityState(PrefService* prefs) {
  if (!base::FeatureList::IsEnabled(
          ntp_features::kNtpSimplificationBookmarkBar)) {
    return;
  }

  auto visibility_state = bookmarks::BookmarkBarVisibilityState::kAlwaysHide;
  if (prefs->GetBoolean(bookmarks::prefs::kShowBookmarkBar)) {
    visibility_state = bookmarks::BookmarkBarVisibilityState::kAlwaysShow;
  } else if (prefs->GetBoolean(
                 bookmarks::prefs::kAlwaysShowBookmarkBarOnNTP)) {
    visibility_state = bookmarks::BookmarkBarVisibilityState::kOnlyShowOnNtp;
  }
  prefs->SetInteger(bookmarks::prefs::kBookmarkBarVisibilityState,
                    static_cast<int>(visibility_state));
}

}  // namespace brave
