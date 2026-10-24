// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveShared
import Foundation
import Shared
import os.log

typealias FavoriteSite = (url: URL, title: String)

struct FavoritesPreloadedData {
  static let youtube = FavoriteSite(
    url: URL(string: "https://www.youtube.com/")!,
    title: "YouTube"
  )
  static let wikipedia = FavoriteSite(
    url: URL(string: "https://www.wikipedia.org/")!,
    title: "Wikipedia"
  )
  static let facebook = FavoriteSite(
    url: URL(string: "https://www.facebook.com/")!,
    title: "Facebook"
  )
  static let brave = FavoriteSite(
    url: URL(string: "https://brave.com/whats-new/")!,
    title: "What's new in Brave"
  )
  static let popularFavorites = [youtube, wikipedia, facebook, brave]

  private struct TopSiteForRegion: Codable {
    let region: String
    let topSites: [TopSite]

    private enum CodingKeys: String, CodingKey {
      case region = "region_id"
      case topSites = "top_sites"
    }
  }

  private struct TopSite: Codable {
    let name: String
    let url: String
  }

  /// Returns a list of websites that should be preloaded for specific region. Currently all users get the same websites.
  // swift-format-ignore
  @concurrent static func getList() async -> [FavoriteSite] {
    // Music Browser: Home teaches the app; only the user's own favourites belong here.
    return []
  }
}
