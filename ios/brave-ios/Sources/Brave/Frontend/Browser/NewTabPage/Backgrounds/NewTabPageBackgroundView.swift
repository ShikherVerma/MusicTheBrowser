// Copyright 2020 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import DesignSystem
import SnapKit
import UIKit

/// Non-interactive contents that appear behind the New Tab Page contents
class NewTabPageBackgroundView: UIView {
  /// The image wallpaper if the user has background images enabled
  let imageView = UIImageView().then {
    $0.contentMode = .scaleAspectFill
    $0.clipsToBounds = false
  }

  func updateImageXOffset(by x: CGFloat) {
    bounds = .init(x: -x, y: 0, width: bounds.width, height: bounds.height)
  }

  override init(frame: CGRect) {
    super.init(frame: frame)

    clipsToBounds = true
    // Music Browser: Home's Carbon text must sit on the matching adaptive page surface.
    backgroundColor = UIColor(braveSystemName: .pageBackground)

    addSubview(imageView)
    imageView.snp.makeConstraints {
      $0.edges.equalToSuperview()
    }
  }

  @available(*, unavailable)
  required init(coder: NSCoder) {
    fatalError()
  }
}
