// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import Shared
import SnapKit
import UIKit

private struct TabsButtonUX {
  static let cornerRadius: CGFloat = 3
  static let borderStrokeWidth: CGFloat = 1.5
}

class TabsButton: UIButton {

  private let countLabel = UILabel().then {
    $0.textAlignment = .center
    $0.isUserInteractionEnabled = false
  }

  private let borderView = UIView().then {
    $0.layer.borderWidth = TabsButtonUX.borderStrokeWidth
    $0.layer.cornerRadius = TabsButtonUX.cornerRadius
    $0.layer.cornerCurve = .continuous
    $0.isUserInteractionEnabled = false
  }

  var browserColors: any BrowserColors = .standard {
    didSet {
      updateForTraitCollectionAndBrowserColors()
    }
  }

  override init(frame: CGRect) {
    super.init(frame: frame)

    accessibilityTraits.insert(.button)
    isAccessibilityElement = true
    accessibilityLabel = Strings.showTabs

    addSubview(borderView)
    addSubview(countLabel)

    // Music Browser: this button opens the music library, not the tab grid.
    // Two-sided pill: globe (web) left, music note in accent circle right.
    borderView.isHidden = true
    countLabel.isHidden = true
    accessibilityLabel = "Music"

    let pillView = UIView()
    pillView.isUserInteractionEnabled = false
    pillView.layer.borderWidth = 1.5
    pillView.layer.borderColor = UIColor.systemGray3.cgColor
    pillView.layer.cornerRadius = 15
    pillView.layer.cornerCurve = .continuous
    addSubview(pillView)
    pillView.snp.makeConstraints {
      $0.center.equalToSuperview()
      $0.width.equalTo(58)
      $0.height.equalTo(30)
    }

    let globeView = UIImageView(image: UIImage(systemName: "globe"))
    globeView.tintColor = .systemGray
    globeView.contentMode = .scaleAspectFit
    pillView.addSubview(globeView)
    globeView.snp.makeConstraints {
      $0.leading.equalToSuperview().offset(8)
      $0.centerY.equalToSuperview()
      $0.width.height.equalTo(16)
    }

    let noteCircle = UIView()
    noteCircle.backgroundColor = .systemGreen
    noteCircle.layer.cornerRadius = 12
    pillView.addSubview(noteCircle)
    noteCircle.snp.makeConstraints {
      $0.trailing.equalToSuperview().inset(3)
      $0.centerY.equalToSuperview()
      $0.width.height.equalTo(24)
    }

    let noteView = UIImageView(image: UIImage(systemName: "music.note"))
    noteView.tintColor = .white
    noteView.contentMode = .scaleAspectFit
    noteCircle.addSubview(noteView)
    noteView.snp.makeConstraints {
      $0.center.equalToSuperview()
      $0.width.height.equalTo(14)
    }

    countLabel.snp.makeConstraints {
      $0.edges.equalToSuperview()
    }
    updateForTraitCollectionAndBrowserColors()

    registerForTraitChanges([
      UITraitUserInterfaceStyle.self,
      UITraitPreferredContentSizeCategory.self,
    ]) { (self: Self, _) in
      self.updateForTraitCollectionAndBrowserColors()
    }
  }

  @available(*, unavailable)
  required init(coder: NSCoder) {
    fatalError()
  }

  override var isHighlighted: Bool {
    didSet {
      let color: UIColor = isHighlighted ? browserColors.iconActive : browserColors.iconDefault
      countLabel.textColor = color
      borderView.layer.borderColor = color.resolvedColor(with: traitCollection).cgColor
    }
  }

  private func updateForTraitCollectionAndBrowserColors() {
    // CGColor's do not get automatic updates
    countLabel.textColor = isHighlighted ? browserColors.iconActive : browserColors.iconDefault
    borderView.layer.borderColor =
      isHighlighted
      ? browserColors.iconActive.cgColor
      : browserColors.iconDefault.resolvedColor(with: traitCollection).cgColor

    let toolbarTraitCollection = UITraitCollection(
      preferredContentSizeCategory: traitCollection.toolbarButtonContentSizeCategory
    )
    let metrics = UIFontMetrics(forTextStyle: .body)
    borderView.snp.remakeConstraints {
      $0.center.equalToSuperview()
      $0.size.equalTo(metrics.scaledValue(for: 20, compatibleWith: toolbarTraitCollection))
    }
    let scaledBorderStrokeWidth = metrics.scaledValue(for: TabsButtonUX.borderStrokeWidth)
    borderView.layer.borderWidth = min(scaledBorderStrokeWidth, 2.0)

    countLabel.font = .systemFont(
      ofSize: UIFont.preferredFont(forTextStyle: .caption2, compatibleWith: toolbarTraitCollection)
        .pointSize,
      weight: .bold
    )
  }

  private var currentCount: Int?

  func updateTabCount(_ count: Int) {
    let count = max(count, 1)
    // Sometimes tabs count state is held in the cloned tabs button.
    let infinity = "\u{221E}"
    let countToBe = (count < 100) ? "\(count)" : infinity
    currentCount = count
    self.countLabel.text = countToBe
    self.accessibilityValue = countToBe
  }

  override func contextMenuInteraction(
    _ interaction: UIContextMenuInteraction,
    willDisplayMenuFor configuration: UIContextMenuConfiguration,
    animator: UIContextMenuInteractionAnimating?
  ) {
    UIImpactFeedbackGenerator(style: .medium).vibrate()
  }
}
