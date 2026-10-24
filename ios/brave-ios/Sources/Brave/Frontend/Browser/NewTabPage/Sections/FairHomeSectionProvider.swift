// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveUI
import CoreData
import Data
import DesignSystem
import Playlist
import PlaylistUI
import Shared
import SwiftUI
import UIKit
import os.log

// Music Browser: the introduction is onboarding; recent saves are the first result.
final class FairHomeSectionProvider: NSObject, NTPObservableSectionProvider, NSFetchedResultsControllerDelegate {
  var sectionDidChange: (() -> Void)?
  private let items: NSFetchedResultsController<PlaylistItem>
  private let openMusic: (PlaylistItem?) -> Void

  init(openMusic: @escaping (PlaylistItem?) -> Void) {
    self.openMusic = openMusic
    items = PlaylistItem.frc()
    items.fetchRequest.sortDescriptors = [NSSortDescriptor(key: "dateAdded", ascending: false)]
    items.fetchRequest.fetchLimit = 5
    super.init()
    items.delegate = self
    do {
      try items.performFetch()
    } catch {
      Logger.module.error("Could not fetch recent music: \(error.localizedDescription)")
    }
  }

  func controllerDidChangeContent(_ controller: NSFetchedResultsController<NSFetchRequestResult>) {
    sectionDidChange?()
  }

  func registerCells(to collectionView: UICollectionView) {
    collectionView.register(FairHomeCell.self)
  }

  func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { 1 }

  func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
    let cell = collectionView.dequeueReusableCell(for: indexPath) as FairHomeCell
    let recent = Array((items.fetchedObjects ?? []).prefix(5))
    cell.contentConfiguration = UIHostingConfiguration {
      VStack(alignment: .leading, spacing: 8) {
        Text("Fair Music Browser")
          .fairFont(.label01)
          .foregroundStyle(Color(braveSystemName: .textSecondary))
        Text("Play some music")
          .fairFont(.heading05)
          .foregroundStyle(Color(braveSystemName: .textPrimary))
        Text("Search for a song or open any music site. Everything you play is saved to Music, ready to play offline.")
          .fairFont(.body02)
          .foregroundStyle(Color(braveSystemName: .textSecondary))
        if !recent.isEmpty {
          Text("Recently saved")
            .fairFont(.heading01)
            .padding(.top, 16)
          VStack(spacing: 0) {
            ForEach(recent) { item in
              Button { self.openMusic(item) } label: {
                FairRecentSongRow(item: item)
              }
              .buttonStyle(.plain)
              Divider()
            }
          }
          Button("Show all in Music") { self.openMusic(nil) }
            .fairFont(.bodyCompact02)
            .frame(minHeight: 48)
            .foregroundStyle(Color(braveSystemName: .textInteractive))
        }
      }
      .frame(maxWidth: .infinity, alignment: .leading)
      .fixedSize(horizontal: false, vertical: true)
    }
    .margins(.all, 0)
    return cell
  }

  func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                      sizeForItemAt indexPath: IndexPath) -> CGSize {
    var size = fittingSizeForCollectionView(collectionView, section: indexPath.section)
    size.height = 200
    return size
  }

  func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                      insetForSectionAt section: Int) -> UIEdgeInsets {
    // No sponsored-image half-screen offset in landscape. Home stays leading-aligned.
    UIEdgeInsets(top: 24, left: 16, bottom: 24, right: 16)
  }
}

private final class FairHomeCell: UICollectionViewCell, CollectionViewReusable {
  override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
    let attributes = layoutAttributes.copy() as! UICollectionViewLayoutAttributes
    attributes.size.height = systemLayoutSizeFitting(
      layoutAttributes.size,
      withHorizontalFittingPriority: .required,
      verticalFittingPriority: .fittingSizeLevel
    ).height
    return attributes
  }
}

private struct FairRecentSongRow: View {
  @ObservedObject var item: PlaylistItem
  @StateObject private var thumbnail = MediaThumbnailLoader()
  @State private var bytes: Int?
  @State private var cacheState: PlaylistDownloadManager.CacheState?
  @State private var progress: Double?

  private var metadata: String {
    let duration = item.duration.isFinite ? max(0, Int(item.duration)) : 0
    let time = duration > 0 ? String(format: "%d:%02d · ", duration / 60, duration % 60) : ""
    switch cacheState {
    case .cached:
      return time + (bytes.map { ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .file) } ?? "Saved offline")
    case .inProgress:
      return time + (progress.map { "Saving… \(Int($0 * 100))%" } ?? "Saving…")
    default:
      return time + "Not saved"
    }
  }

  var body: some View {
    HStack(alignment: .top, spacing: 16) {
      ZStack {
        Color(braveSystemName: .containerDisabled)
        if let image = thumbnail.image {
          Image(uiImage: image).resizable().scaledToFill()
        } else {
          Image(systemName: "music.note")
            .foregroundStyle(Color(braveSystemName: .textSecondary))
        }
      }
      .frame(width: 48, height: 48)
      .clipped()
      .overlay { Rectangle().strokeBorder(Color(braveSystemName: .dividerSubtle), lineWidth: 1) }
      .accessibilityHidden(true)
      VStack(alignment: .leading, spacing: 4) {
        Text(item.name).fairFont(.bodyCompact02).lineLimit(2)
          .foregroundStyle(Color(braveSystemName: .textPrimary))
        Text(metadata).fairFont(.code02)
          .foregroundStyle(Color(braveSystemName: .textSecondary))
      }
      .frame(maxWidth: .infinity, alignment: .leading)
    }
    .padding(.vertical, 8)
    .contentShape(.rect)
    .task(id: item.id) {
      if let uuid = item.uuid { cacheState = await PlaylistManager.shared.cacheState(for: uuid) }
      if let assetURL = URL(string: item.mediaSrc), let pageURL = URL(string: item.pageSrc) {
        try? await thumbnail.loadThumbnail(assetURL: assetURL, pageURL: pageURL)
      }
    }
    .task(id: item.cachedData) {
      guard let data = item.cachedData, let url = await PlaylistItem.resolvingCachedData(data) else { return }
      if let values = try? url.resourceValues(forKeys: [.fileSizeKey, .isDirectoryKey]) {
        if values.isDirectory == true {
          let size = try? await AsyncFileManager.default.sizeOfDirectory(at: url)
          bytes = size.map { Int($0) }
        } else {
          bytes = values.fileSize
        }
      }
    }
    .onReceive(PlaylistManager.shared.downloadStateChanged) { output in
      if output.id == item.uuid { cacheState = output.state }
    }
    .onReceive(PlaylistManager.shared.downloadProgressUpdated) { output in
      if output.id == item.uuid { progress = output.percentComplete }
    }
  }
}
