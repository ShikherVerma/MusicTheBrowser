// Copyright 2023 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import NalaAssets
import SwiftUI
import UIKit

extension Color {
  /// Initialize a `Color` with a color resource.
  public init(braveSystemName resource: FigmaColorResource) {
    // Music Browser: prefer the Carbon override, retaining Nala as the upstream fallback.
    self.init(uiColor: UIColor(braveSystemName: resource))
  }
}

extension UIColor {
  /// Initialize a `UIColor` with a color resource.
  public convenience init(braveSystemName resource: FigmaColorResource) {
    // Music Browser: preserve dynamic light/dark colours when selecting the asset bundle.
    let bundle: Bundle = UIColor(named: resource.name, in: .module, compatibleWith: nil) != nil
      ? .module : .nalaAssets
    self.init(named: resource.name, in: bundle, compatibleWith: nil)!
  }
}
