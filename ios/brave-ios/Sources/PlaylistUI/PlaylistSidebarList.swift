// Copyright (c) 2024 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveCore
import BraveShared
import CoreData
import Data
import DesignSystem
import Foundation
import Playlist
import Strings
import SwiftUI

struct PlaylistSidebarList: View {
  var folders: [PlaylistFolder]
  var selectedFolderID: PlaylistFolder.ID
  @Binding var selectedItemID: PlaylistItem.ID?
  var isPlaying: Bool
  var onPlaylistUpdated: () -> Void

  @FetchRequest private var items: FetchedResults<PlaylistItem>

  init(
    folders: [PlaylistFolder],
    folderID: PlaylistFolder.ID,
    selectedItemID: Binding<PlaylistItem.ID?>,
    isPlaying: Bool,
    onPlaylistUpdated: @escaping () -> Void
  ) {
    self.folders = folders
    self.selectedFolderID = folderID
    self._items = FetchRequest<PlaylistItem>(
      sortDescriptors: [
        .init(keyPath: \PlaylistItem.order, ascending: true),
        .init(keyPath: \PlaylistItem.dateAdded, ascending: false),
      ],
      predicate: .init(format: "playlistFolder.uuid == %@", folderID)
    )
    self._selectedItemID = selectedItemID
    self.isPlaying = isPlaying
    self.onPlaylistUpdated = onPlaylistUpdated
  }

  var body: some View {
    LazyVStack(spacing: 0) {
      if items.isEmpty {
        PlaylistSidebarContentUnavailableView()
      }
      PlaylistItemList(
        items: Array(items),
        folders: folders,
        selectedFolderID: selectedFolderID,
        selectedItemID: $selectedItemID,
        isPlaying: isPlaying,
        onItemSelected: { item in
          selectedItemID = item.id
        },
        onPlaylistUpdated: onPlaylistUpdated
      )
    }
    .frame(maxWidth: .infinity, alignment: .leading)
  }
}

struct PlaylistSidebarListHeader: View {
  var folders: [PlaylistFolder]
  @Binding var selectedFolder: PlaylistFolder
  @Binding var isNewPlaylistAlertPresented: Bool
  @Binding var isEditModePresented: Bool
  var onPlayAll: (Bool) -> Void

  // Music Browser: show the lifetime count only near the free limit.
  @ObservedObject private var saves = FairSaveCount.shared
  @ObservedObject private var unlock = FairUnlock.shared
  @State private var isUnlockPresented = false

  @State private var totalSizeOnDisk = Measurement<UnitInformationStorage>(value: 0, unit: .bytes)
  @State private var isDeletePlaylistConfirmationActive: Bool = false
  @State private var isRenamePlaylistAlertPresented: Bool = false

  @FetchRequest private var selectedFolderItems: FetchedResults<PlaylistItem>

  init(
    folders: [PlaylistFolder],
    selectedFolder: Binding<PlaylistFolder>,
    isNewPlaylistAlertPresented: Binding<Bool>,
    isEditModePresented: Binding<Bool>,
    onPlayAll: @escaping (Bool) -> Void
  ) {
    self.folders = folders
    self._selectedFolder = selectedFolder
    self._isNewPlaylistAlertPresented = isNewPlaylistAlertPresented
    self._isEditModePresented = isEditModePresented
    self.onPlayAll = onPlayAll
    self._selectedFolderItems = .init(
      sortDescriptors: [],
      predicate: .init(format: "playlistFolder.uuid == %@", selectedFolder.id)
    )
  }

  @MainActor private func calculateTotalSizeOnDisk(for folder: PlaylistFolder) async {
    // Cache-first hides the total size in the header, so skip the (potentially expensive) disk scan.
    if FeatureList.kPlaylistCacheFirstEnabled.enabled {
      totalSizeOnDisk = .init(value: 0, unit: .bytes)
      return
    }
    // Since we're consuming CoreData we need to make sure those accesses happen on main
    let items = selectedFolderItems
    var totalSize: Int = 0
    for item in items {
      guard let cachedData = item.cachedData else { continue }
      totalSize += await Task.detached {
        guard let cachedDataURL = await PlaylistItem.resolvingCachedData(cachedData) else {
          return 0
        }
        // No CoreData usage allowed in here
        do {
          let values = try cachedDataURL.resourceValues(forKeys: [.fileSizeKey, .isDirectoryKey])
          if values.isDirectory == true {
            // This item is an HLS stream saved as a movpkg, get the size of the directory instead
            return Int(try await AsyncFileManager.default.sizeOfDirectory(at: cachedDataURL))
          }
          return values.fileSize ?? 0
        } catch {
          return 0
        }
      }.value
    }
    totalSizeOnDisk = .init(value: Double(totalSize), unit: .bytes)
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      HStack(alignment: .top, spacing: 16) {
        VStack(alignment: .leading) {
          Menu {
            Picker("", selection: $selectedFolder) {
              ForEach(folders, id: \.objectID) { folder in
                Label(folder.title ?? "", braveSystemImage: "leo.product.playlist")
                  .tag(folder)
              }
            }
            .pickerStyle(.inline)
            Divider()
            Button {
              isNewPlaylistAlertPresented = true
            } label: {
              Label(Strings.Playlist.newPlaylistButtonTitle, braveSystemImage: "leo.plus.add")
            }
          } label: {
            Text("\(selectedFolder.title ?? "") \(Image(braveSystemName: "leo.carat.down"))")
              .fairFont(.heading02)
              .foregroundStyle(Color(braveSystemName: .textPrimary))
              .contentShape(.rect)
          }
          HStack {
            let items = selectedFolderItems
            let totalDuration = Duration.seconds(
              items.reduce(
                0,
                { total, item in
                  if !item.duration.isFinite || item.duration == .greatestFiniteMagnitude {
                    // Skip infinite and max values (live videos)
                    return total
                  }
                  return total + item.duration
                }
              )
            )
            let totalSize = totalSizeOnDisk
            Text(
              items.count == 1
                ? Strings.Playlist.itemCountSingular
                : String.localizedStringWithFormat(Strings.Playlist.itemCountPlural, items.count)
            )
            if !items.isEmpty {
              Text(totalDuration, format: .units(width: .narrow, maximumUnitCount: 2))
            }
            // Cache-first manages the cache automatically, so the on-disk size isn't surfaced.
            if !totalSize.value.isZero, !FeatureList.kPlaylistCacheFirstEnabled.enabled {
              Text(totalSizeOnDisk, format: .byteCount(style: .file))
            }
          }
          .fairFont(.code02)
          if !unlock.isUnlocked && saves.count >= Int(ceil(Double(saves.limit) * 0.9)) {
            Text("\(saves.count.formatted()) of \(saves.limit.formatted()) free songs saved")
              .fairFont(.code02)
            Button("Unlock") { isUnlockPresented = true }
              .frame(minHeight: 48)
              .tint(Color(braveSystemName: .textInteractive))
          }
          if unlock.didUnlock {
            Text("Unlocked. Every song you play is saved again.")
              .fairFont(.body01)
          }
        }
        Spacer()
        Menu {
          if selectedFolder.uuid != PlaylistFolder.savedFolderUUID {
            Button {
              isRenamePlaylistAlertPresented = true
            } label: {
              Label(Strings.Playlist.renamePlaylist, braveSystemImage: "leo.edit.pencil")
            }
            Button(role: .destructive) {
              isDeletePlaylistConfirmationActive = true
            } label: {
              Label(Strings.Playlist.deletePlaylist, braveSystemImage: "leo.trash")
            }
          }
        } label: {
          Text(Strings.Playlist.editPlaylistShortTitle)
            .fontWeight(.semibold)
        } primaryAction: {
          isEditModePresented = true
        }
        .foregroundStyle(Color(braveSystemName: .textPrimary))
        .disabled(
          selectedFolder.uuid == PlaylistFolder.savedFolderUUID
            && selectedFolder.playlistItems?.isEmpty == true
        )
      }
      .padding()
      // Music Browser: a flush 50/50 Carbon action pair, with text that can grow.
      HStack(spacing: 0) {
        Button("Play all") { onPlayAll(false) }
          .frame(maxWidth: .infinity, minHeight: 48)
          .foregroundStyle(.white)
          .background(Color(braveSystemName: .buttonBackground))
        Button("Shuffle") { onPlayAll(true) }
          .frame(maxWidth: .infinity, minHeight: 48)
          .foregroundStyle(Color(braveSystemName: .textPrimary))
          .overlay { Rectangle().strokeBorder(Color(braveSystemName: .dividerStrong), lineWidth: 1) }
      }
      .fairFont(.bodyCompact02)
      .buttonStyle(.plain)
      .disabled(selectedFolderItems.isEmpty)
      .padding(.horizontal, 16)
      .padding(.bottom, 16)
    }
    .sheet(isPresented: $isUnlockPresented) { FairUnlockView() }
    .onDisappear { unlock.clearUnlockNotice() }
    .task {
      await calculateTotalSizeOnDisk(for: selectedFolder)
    }
    .onChange(of: selectedFolderItems.map(\.cachedData)) { _, _ in
      Task {
        await calculateTotalSizeOnDisk(for: selectedFolder)
      }
    }
    .editActions(
      for: selectedFolder,
      isDeletePlaylistConfirmationPresented: $isDeletePlaylistConfirmationActive,
      isRenamePlaylistAlertPresented: $isRenamePlaylistAlertPresented
    )
  }
}

struct PlaylistSidebarContentUnavailableView: View {
  var body: some View {
    // Music Browser: no third-party site logos; the switcher is the action.
    VStack(alignment: .leading, spacing: 16) {
      Image(systemName: "music.note")
        .font(.system(size: 40, weight: .light))
        .foregroundStyle(Color(braveSystemName: .textSecondary))
      Text("Your music lives here").fairFont(.heading03)
      Text("Play music on any website and it's saved here automatically, ready to play offline.")
        .fairFont(.body01)
        .foregroundStyle(Color(braveSystemName: .textSecondary))
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(16)
  }
}

#if DEBUG
#Preview {
  PlaylistSidebarList(
    folders: [],
    folderID: "",
    selectedItemID: .constant(nil),
    isPlaying: false,
    onPlaylistUpdated: {}
  )
}
#endif
