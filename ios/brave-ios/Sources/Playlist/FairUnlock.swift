// Copyright 2026 The Music Browser Authors. MPL-2.0.

import Combine
import Foundation
import Preferences
import StoreKit

// Music Browser: StoreKit alone is the purchase authority. No Brave receipt server.
@MainActor
public final class FairUnlock: ObservableObject {
  public static let shared = FairUnlock()
  public static let productID = "fair_music_browser_unlock"

  @Published public private(set) var isUnlocked = Preferences.Playlist.unlimitedSongs.value
  @Published public private(set) var product: Product?
  @Published public private(set) var isBusy = false
  @Published public private(set) var errorMessage: String?
  @Published public private(set) var didUnlock = false
  private var updates: Task<Void, Never>?

  private init() {
    updates = Task { [weak self] in
      for await result in Transaction.updates {
        guard let self else { return }
        if case .verified(let transaction) = result,
          transaction.productID == Self.productID
        {
          apply(transaction)
          await transaction.finish()
        }
      }
    }
    Task { await refreshEntitlements() }
  }

  private func apply(_ transaction: Transaction) {
    let valid = transaction.revocationDate == nil
      && (transaction.expirationDate.map { $0 > Date.now } ?? true)
    let wasUnlocked = isUnlocked
    isUnlocked = valid
    Preferences.Playlist.unlimitedSongs.value = valid
    if valid && !wasUnlocked { didUnlock = true }
  }

  public func refreshEntitlements() async {
    // An empty/offline result must not erase the cached unlock. Verified revoked
    // transactions do erase it, including those delivered by the updates listener.
    var foundEntitlement = false
    for await result in Transaction.currentEntitlements {
      if case .verified(let transaction) = result, transaction.productID == Self.productID {
        apply(transaction)
        foundEntitlement = true
      }
    }
    // Refunded/revoked purchases no longer appear in currentEntitlements. Their
    // latest signed transaction still lets us invalidate a cached unlock at launch.
    if !foundEntitlement, let latest = await Transaction.latest(for: Self.productID),
      case .verified(let transaction) = latest
    {
      apply(transaction)
    }
  }

  public func loadProduct() async {
    guard product == nil else { return }
    errorMessage = nil
    do {
      product = try await Product.products(for: [Self.productID]).first
      if product == nil { errorMessage = "Can't reach the App Store. Try again later." }
    } catch {
      errorMessage = "Can't reach the App Store. Try again later."
    }
  }

  public func purchase() async {
    guard let product, !isBusy else { return }
    isBusy = true
    errorMessage = nil
    defer { isBusy = false }
    do {
      switch try await product.purchase() {
      case .success(.verified(let transaction)):
        apply(transaction)
        await transaction.finish()
      case .success(.unverified):
        errorMessage = "The purchase couldn't be verified. Please try Restore purchase."
      case .pending:
        errorMessage = "Purchase awaiting approval. Your songs are still available."
      case .userCancelled:
        break
      @unknown default:
        errorMessage = "The purchase couldn't be completed. Try again later."
      }
    } catch {
      errorMessage = "The purchase couldn't be completed. Try again later."
    }
  }

  public func restore() async {
    guard !isBusy else { return }
    isBusy = true
    errorMessage = nil
    defer { isBusy = false }
    do {
      try await AppStore.sync()
      await refreshEntitlements()
      if !isUnlocked { errorMessage = "No purchase found for this Apple Account." }
    } catch {
      errorMessage = "Can't restore purchases right now. Try again later."
    }
  }

  public func shouldPresentAfterRefusedSave(now: Date = .now) -> Bool {
    guard !isUnlocked else { return false }
    if let last = Preferences.Playlist.lastUnlockRefusal.value,
      now.timeIntervalSince(last) < 24 * 60 * 60
    { return false }
    Preferences.Playlist.lastUnlockRefusal.value = now
    return true
  }

  public func clearUnlockNotice() { didUnlock = false }
}
