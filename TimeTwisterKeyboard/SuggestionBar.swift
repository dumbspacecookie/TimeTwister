//
//  SuggestionBar.swift
//  TimeTwisterKeyboard
//
//  Created by dumbspacecookie on 4/22/26.
//

import UIKit
import TimeTwisterCore

/// Thin bar above the keys. Shows either a hint ("Type a time and TZ to convert")
/// or a tappable chip previewing the stamp we'd insert.
public final class SuggestionBar: UIView {

    public var onTap: ((DetectedTime) -> Void)?

    private let chip = UIButton(type: .system)
    private let hint = UILabel()
    private var currentDetected: DetectedTime?

    public override init(frame: CGRect) {
        super.init(frame: frame)
        build()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        build()
    }

    public func setSuggestion(detected: DetectedTime?, targets: [TimeZone]) {
        currentDetected = detected
        if let d = detected {
            let preview = TimeConverter.renderStamp(for: d, targets: targets)
            chip.setTitle(preview, for: .normal)
            chip.isHidden = false
            hint.isHidden = true
        } else {
            chip.isHidden = true
            hint.isHidden = false
        }
    }

    private func build() {
        backgroundColor = .secondarySystemBackground

        hint.text = "Type a time with a TZ (e.g. 5pm CT) to convert"
        hint.font = .systemFont(ofSize: 13)
        hint.textColor = .secondaryLabel
        hint.textAlignment = .center
        hint.translatesAutoresizingMaskIntoConstraints = false
        addSubview(hint)

        chip.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        chip.setTitleColor(.white, for: .normal)
        chip.backgroundColor = .systemBlue
        chip.contentEdgeInsets = UIEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        chip.layer.cornerRadius = 14
        chip.translatesAutoresizingMaskIntoConstraints = false
        chip.isHidden = true
        chip.addTarget(self, action: #selector(tapChip), for: .touchUpInside)
        addSubview(chip)

        NSLayoutConstraint.activate([
            hint.centerYAnchor.constraint(equalTo: centerYAnchor),
            hint.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            hint.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),

            chip.centerYAnchor.constraint(equalTo: centerYAnchor),
            chip.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 8),
            chip.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -8),
            chip.centerXAnchor.constraint(equalTo: centerXAnchor),
            chip.heightAnchor.constraint(equalToConstant: 28),
        ])
    }

    @objc private func tapChip() {
        guard let d = currentDetected else { return }
        onTap?(d)
    }
}
