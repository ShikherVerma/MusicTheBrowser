// Copyright 2026 The Music Browser Authors. MPL-2.0.

import DesignSystem
import Playlist
import SwiftUI

// Music Browser: count-only unlock. The browser and existing songs never stop working.
public struct FairUnlockView: View {
  @ObservedObject private var unlock = FairUnlock.shared
  @ObservedObject private var saves = FairSaveCount.shared
  @Environment(\.dismiss) private var dismiss
  @State private var loading = true

  public init() {}

  public var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 16) {
        Text("Fair Music Browser")
          .fairFont(.label01)
          .foregroundStyle(Color(braveSystemName: .textSecondary))
        Text("Keep saving everything you play").fairFont(.heading03)
        Text("Unlimited saved songs. One payment, yours to keep. No account, no subscription.")
          .fairFont(.body02)
        Text("\(saves.count.formatted()) of \(saves.limit.formatted()) free songs saved")
          .fairFont(.code02)
          .foregroundStyle(Color(braveSystemName: .textSecondary))
        Button {
          Task { await unlock.purchase() }
        } label: {
          HStack {
            Text(unlock.product.map { "Unlock for \($0.displayPrice)" }
              ?? (loading ? "Loading price…" : "Price unavailable"))
            Spacer()
            if loading || unlock.isBusy { ProgressView().tint(.white) }
          }
          .fairFont(.bodyCompact02)
          .padding(16)
          .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
          .foregroundStyle(.white)
          .background(Color(braveSystemName: .buttonBackground))
        }
        .buttonStyle(.plain)
        .disabled(loading || unlock.product == nil || unlock.isBusy)
        .opacity(loading || unlock.product == nil || unlock.isBusy ? 0.5 : 1)
        if let message = unlock.errorMessage {
          Text(message).fairFont(.helperText01)
            .foregroundStyle(Color(braveSystemName: .textSecondary))
        }
        Button("Restore purchase") { Task { await unlock.restore() } }
          .disabled(unlock.isBusy)
          .frame(minHeight: 48)
        Button("Not now") { dismiss() }
          .frame(minHeight: 48)
      }
      .fairFont(.bodyCompact02)
      .foregroundStyle(Color(braveSystemName: .textPrimary))
      .tint(Color(braveSystemName: .textInteractive))
      .padding(16)
    }
    .background(Color(braveSystemName: .containerBackground))
    .presentationDetents([.medium, .large])
    .presentationDragIndicator(.visible)
    .task {
      await unlock.loadProduct()
      loading = false
    }
    .onChange(of: unlock.isUnlocked) { _, unlocked in
      if unlocked { dismiss() }
    }
  }
}
