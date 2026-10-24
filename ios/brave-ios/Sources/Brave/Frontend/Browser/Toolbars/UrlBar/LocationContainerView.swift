// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import DesignSystem
import UIKit

// Music Browser: the Carbon field is flat and square. The search input explicitly
// selects the focus ring; the read-only location bar keeps only its bottom rule.
class LocationContainerView: UIView {
  let contentView = UIView()
  private let bottomRule = UIView()

  var isFieldFocused = false {
    didSet { updateBorder() }
  }

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
    addSubview(contentView)
    addSubview(bottomRule)
    bottomRule.isUserInteractionEnabled = false
    registerForTraitChanges([UITraitUserInterfaceStyle.self]) {
      (self: LocationContainerView, _: UITraitCollection) in
      self.updateBorder()
    }
    updateBorder()
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError() }

  private func updateBorder() {
    contentView.layer.borderWidth = isFieldFocused ? 2 : 0
    contentView.layer.borderColor = UIColor(braveSystemName: .textInteractive).cgColor
    bottomRule.backgroundColor = UIColor(braveSystemName: .dividerStrong)
    bottomRule.isHidden = isFieldFocused
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    contentView.frame = bounds
    bottomRule.frame = CGRect(x: 0, y: bounds.height - 1, width: bounds.width, height: 1)
  }
}
