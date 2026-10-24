// Copyright 2026 The Music Browser Authors. MPL-2.0.

import Combine
import Data
import Foundation
import Preferences
import Security

// Music Browser: reservations keep concurrent detector callbacks within the limit.
// Undo-remove is not a new save and does not reserve or consume a song.
public struct FairSaveBudget {
  public private(set) var count: Int
  private var pending: Set<String> = []

  public init(count: Int) { self.count = max(0, count) }

  public mutating func reserve(id: String, limit: Int, unlocked: Bool) -> Bool {
    guard !pending.contains(id), unlocked || count + pending.count < limit else { return false }
    pending.insert(id)
    return true
  }

  public mutating func finish(id: String, created: Bool) {
    guard pending.remove(id) != nil else { return }
    if created { count += 1 }
  }

  public func containsPending(id: String) -> Bool { pending.contains(id) }
  public var hasPendingSaves: Bool { !pending.isEmpty }
}

@MainActor
public final class FairSaveCount: ObservableObject {
  public static let shared = FairSaveCount()
  @Published public private(set) var count: Int
  private var budget: FairSaveBudget
  private var hasReadKeychain = false
  private let service = "com.shikherverma.musicbrowser.fair"

  public var limit: Int {
    #if DEBUG
    let override = UserDefaults.standard.integer(forKey: "FairFreeSongLimit")
    if override > 0 { return override }
    #endif
    return 1_000
  }

  private init() {
    let initial = max(Preferences.Playlist.savedSongCount.value, PlaylistItem.count())
    count = initial
    budget = FairSaveBudget(count: initial)
    reconcileKeychain()
    Preferences.Playlist.savedSongCount.value = count
  }

  private var query: [String: Any] {
    [kSecClass as String: kSecClassGenericPassword,
     kSecAttrService as String: service,
     kSecAttrAccount as String: "savedSongCount",
     kSecAttrSynchronizable as String: false]
  }

  // Never overwrite the reinstall-proof copy until a read succeeded. A locked or
  // unavailable Keychain leaves the local counter intact and is retried next save.
  private func reconcileKeychain() {
    guard !hasReadKeychain else { return }
    var read = query
    read[kSecReturnData as String] = true
    read[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    let status = SecItemCopyMatching(read as CFDictionary, &result)
    if status == errSecSuccess {
      guard let data = result as? Foundation.Data,
        let string = String(data: data, encoding: .utf8), let stored = Int(string), stored >= 0
      else { return }
      count = max(count, stored)
      // Reconciliation only runs before reservations are made.
      budget = FairSaveBudget(count: count)
    } else if status != errSecItemNotFound {
      return
    }
    hasReadKeychain = true
    persist()
  }

  private func persist() {
    Preferences.Playlist.savedSongCount.value = count
    guard hasReadKeychain else { return }
    let attributes: [String: Any] = [
      kSecValueData as String: Foundation.Data(String(count).utf8),
      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    if status == errSecItemNotFound {
      _ = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
    }
  }

  public func reserve(id: String, unlocked: Bool) -> Bool {
    // Retry a previously unavailable Keychain only when nothing is in flight.
    if !hasReadKeychain && !budget.hasPendingSaves { reconcileKeychain() }
    return budget.reserve(id: id, limit: limit, unlocked: unlocked)
  }

  public func isPending(id: String) -> Bool { budget.containsPending(id: id) }

  public func finish(id: String, created: Bool) {
    budget.finish(id: id, created: created)
    count = budget.count
    persist()
  }
}
