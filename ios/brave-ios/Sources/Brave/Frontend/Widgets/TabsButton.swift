// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import DesignSystem
import Shared
import SnapKit
import UIKit

// Music Browser: retain ToolbarProtocol's UIButton seam, not the old tab-count UI.
class TabsButton: UIButton {
  var browserColors: any BrowserColors = .standard
  private var modeSwitcher: ModeSwitcherHost!

  override init(frame: CGRect) {
    super.init(frame: frame)
    isAccessibilityElement = false
    modeSwitcher = ModeSwitcherHost(selection: .browse) { [weak self] mode in
      if mode == .music { self?.sendActions(for: .touchUpInside) }
    }
    addSubview(modeSwitcher)
    modeSwitcher.snp.makeConstraints {
      $0.edges.equalToSuperview()
    }
    setContentCompressionResistancePriority(.required, for: .horizontal)
    setContentCompressionResistancePriority(.required, for: .vertical)
    registerForTraitChanges([UITraitPreferredContentSizeCategory.self]) { (self: Self, _) in
      self.invalidateIntrinsicContentSize()
    }
  }

  override var intrinsicContentSize: CGSize {
    modeSwitcher?.intrinsicContentSize ?? CGSize(width: 192, height: 48)
  }

  // Music Browser: callers still refresh the tab count, but single-tab mode has no badge.
  func updateTabCount(_ count: Int) {}

  @available(*, unavailable)
  required init(coder: NSCoder) { fatalError() }
}
