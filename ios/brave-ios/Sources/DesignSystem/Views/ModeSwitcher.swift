// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

// Music Browser: the same content switcher in Browse and Music.
public struct ModeSwitcher: View {
  public enum Mode: String, CaseIterable {
    case browse = "Browse"
    case music = "Music"
  }

  public let selection: Mode
  public var onSelect: (Mode) -> Void

  public init(selection: Mode, onSelect: @escaping (Mode) -> Void) {
    self.selection = selection
    self.onSelect = onSelect
  }

  public var body: some View {
    HStack(spacing: 0) {
      ForEach(Mode.allCases, id: \.self) { mode in
        Button {
          if mode != selection { onSelect(mode) }
        } label: {
          ZStack(alignment: .center) {
            Text(mode.rawValue)
              .fairFont(.bodyCompact02)
              .multilineTextAlignment(.center)
              .padding(.horizontal, 16)
              .padding(.vertical, 8)
          }
            .frame(minWidth: 96, maxWidth: .infinity, minHeight: 48, alignment: .center)
            .foregroundStyle(Color(braveSystemName: mode == selection ? .schemesInverseOnSurface : .textSecondary))
            .background(mode == selection ? Color(braveSystemName: .textPrimary) : .clear)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .accessibilityLabel("Mode, \(mode.rawValue)")
        .accessibilityValue(mode == .browse ? "1 of 2" : "2 of 2")
        .accessibilityAddTraits(mode == selection ? .isSelected : [])
        .accessibilityIdentifier("FairMode.\(mode.rawValue)")
      }
    }
    .fixedSize(horizontal: false, vertical: true)
    .background(Color(braveSystemName: .pageBackground))
    .clipShape(.rect(cornerRadius: 4))
    .overlay {
      RoundedRectangle(cornerRadius: 4)
        .strokeBorder(Color(braveSystemName: .textPrimary), lineWidth: 1)
        .allowsHitTesting(false)
    }
    .accessibilityElement(children: .contain)
  }
}

// A UIHostingConfiguration avoids an unparented UIHostingController inside a UIButton.
public final class ModeSwitcherHost: UIView {
  private let content: UIView

  public init(selection: ModeSwitcher.Mode, onSelect: @escaping (ModeSwitcher.Mode) -> Void) {
    content = UIHostingConfiguration {
      ModeSwitcher(selection: selection, onSelect: onSelect)
    }
    .margins(.all, 0)
    .makeContentView()
    super.init(frame: .zero)
    content.translatesAutoresizingMaskIntoConstraints = false
    addSubview(content)
    NSLayoutConstraint.activate([
      content.leadingAnchor.constraint(equalTo: leadingAnchor),
      content.trailingAnchor.constraint(equalTo: trailingAnchor),
      content.topAnchor.constraint(equalTo: topAnchor),
      content.bottomAnchor.constraint(equalTo: bottomAnchor),
    ])
  }

  public override var intrinsicContentSize: CGSize {
    content.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError() }
}
