// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

// Music Browser: Carbon productive type, on SF / SF Mono with Dynamic Type.
public enum FairType {
  case label01, helperText01, code02, bodyCompact01, body01, bodyCompact02, body02
  case headingCompact01, heading01, heading02, heading03, heading04, heading05, heading06

  public var size: CGFloat {
    switch self {
    case .label01, .helperText01: return 12
    case .code02, .bodyCompact01, .body01, .headingCompact01, .heading01: return 14
    case .bodyCompact02, .body02, .heading02: return 16
    case .heading03: return 20
    case .heading04: return 28
    case .heading05: return 32
    case .heading06: return 42
    }
  }

  public var lineHeight: CGFloat {
    switch self {
    case .label01, .helperText01: return 16
    case .bodyCompact01, .headingCompact01: return 18
    case .code02, .body01, .heading01: return 20
    case .bodyCompact02: return 22
    case .body02, .heading02: return 24
    case .heading03: return 28
    case .heading04: return 36
    case .heading05: return 40
    case .heading06: return 50
    }
  }

  fileprivate var weight: Font.Weight {
    switch self {
    case .headingCompact01, .heading01, .heading02: return .semibold
    case .heading06: return .light
    default: return .regular
    }
  }

  fileprivate var textStyle: Font.TextStyle {
    switch self {
    case .label01, .helperText01: return .caption
    case .code02, .bodyCompact01, .body01, .headingCompact01, .heading01: return .subheadline
    case .heading03: return .title3
    case .heading04: return .title2
    case .heading05, .heading06: return .largeTitle
    default: return .body
    }
  }

  public func uiFont(compatibleWith traits: UITraitCollection? = nil) -> UIFont {
    let weight: UIFont.Weight = self.weight == .semibold ? .semibold
      : (self.weight == .light ? .light : .regular)
    let font = self == .code02
      ? UIFont.monospacedSystemFont(ofSize: size, weight: weight)
      : UIFont.systemFont(ofSize: size, weight: weight)
    let style: UIFont.TextStyle
    switch self {
    case .label01, .helperText01: style = .caption1
    case .code02, .bodyCompact01, .body01, .headingCompact01, .heading01: style = .subheadline
    case .heading03: style = .title3
    case .heading04: style = .title2
    case .heading05, .heading06: style = .largeTitle
    default: style = .body
    }
    return UIFontMetrics(forTextStyle: style).scaledFont(for: font, compatibleWith: traits)
  }
}

private struct FairTypeModifier: ViewModifier {
  let token: FairType
  @ScaledMetric private var size: CGFloat
  @ScaledMetric private var lineHeight: CGFloat

  init(_ token: FairType) {
    self.token = token
    _size = ScaledMetric(wrappedValue: token.size, relativeTo: token.textStyle)
    _lineHeight = ScaledMetric(wrappedValue: token.lineHeight, relativeTo: token.textStyle)
  }

  func body(content: Content) -> some View {
    content
      .font(.system(size: size, weight: token.weight, design: token == .code02 ? .monospaced : .default))
      .lineSpacing(max(0, lineHeight - size * 1.2))
  }
}

extension View {
  public func fairFont(_ token: FairType) -> some View {
    modifier(FairTypeModifier(token))
  }
}
