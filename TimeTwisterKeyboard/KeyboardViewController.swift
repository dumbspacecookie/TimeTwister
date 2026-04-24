//
//  KeyboardViewController.swift
//  TimeTwisterKeyboard
//
//  Created by dumbspacecookie on 4/22/26.
//

import UIKit
import TimeTwisterCore

/// TimeTwister keyboard extension.
///
/// Design note: iOS custom keyboards cannot inject text *after* the user taps
/// send, and they cannot read already-sent messages. So our strategy is:
/// 1. Watch the current document context as the user types.
/// 2. When we detect a time phrase ("5pm CT"), show a chip in the suggestion bar.
/// 3. Tapping the chip replaces the phrase with a multi-TZ stamp before send.
///
/// v0.1 keyboard body is intentionally minimal — a single row of common keys
/// plus backspace / space / return / globe. Users will spend most of their
/// typing time on the system keyboard; TimeTwister is a tool they switch to
/// briefly to convert.
public final class KeyboardViewController: UIInputViewController {

    private let suggestionBar = SuggestionBar()
    private var lastSeenContext: String = ""

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        buildUI()
        suggestionBar.onTap = { [weak self] detected in
            self?.apply(detected: detected)
        }
    }

    public override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        refreshSuggestions()
    }

    public override func selectionDidChange(_ textInput: UITextInput?) {
        super.selectionDidChange(textInput)
        refreshSuggestions()
    }

    // MARK: - Suggestion logic

    private func refreshSuggestions() {
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let after = textDocumentProxy.documentContextAfterInput ?? ""
        let context = before + after
        guard context != lastSeenContext else { return }
        lastSeenContext = context

        // We look at the most recent time phrase before the cursor —
        // that's what the user just typed and is most likely to want converted.
        let beforeMatches = TimeParser.detect(in: before)
        let targets = UserPreferences.shared.targetZones
        suggestionBar.setSuggestion(
            detected: beforeMatches.last,
            targets: targets
        )
    }

    private func apply(detected: DetectedTime) {
        let targets = UserPreferences.shared.targetZones
        let stamp = TimeConverter.renderStamp(for: detected, targets: targets)

        // Delete the original phrase from the document. documentContextBeforeInput
        // gives us the text ending at the cursor, so we know how far back to
        // delete.
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let nsBefore = before as NSString
        let tail = nsBefore.length - detected.range.location - detected.range.length
        if tail == 0 {
            // The detected phrase sits right at the cursor. Delete its characters.
            for _ in 0..<detected.range.length {
                textDocumentProxy.deleteBackward()
            }
            textDocumentProxy.insertText(stamp)
        } else {
            // Phrase isn't at the cursor edge (unusual — user moved cursor).
            // Safest fallback: append the stamp in-place after the cursor.
            textDocumentProxy.insertText(" \(stamp)")
        }
        lastSeenContext = ""
        refreshSuggestions()
    }

    // MARK: - UI

    private func buildUI() {
        suggestionBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(suggestionBar)

        let keysRow = makeKeysRow()
        keysRow.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(keysRow)

        NSLayoutConstraint.activate([
            suggestionBar.topAnchor.constraint(equalTo: view.topAnchor),
            suggestionBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            suggestionBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            suggestionBar.heightAnchor.constraint(equalToConstant: 44),

            keysRow.topAnchor.constraint(equalTo: suggestionBar.bottomAnchor, constant: 4),
            keysRow.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 4),
            keysRow.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -4),
            keysRow.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -4),
            keysRow.heightAnchor.constraint(equalToConstant: 44),
        ])
    }

    private func makeKeysRow() -> UIStackView {
        // Minimal utility row — users will rely on the system keyboard for
        // actual typing. Globe + space + backspace + return cover the basics.
        let globe = keyButton(title: "🌐", action: #selector(handleGlobe))
        let space = keyButton(title: "space", action: #selector(handleSpace))
        let delete = keyButton(title: "⌫", action: #selector(handleDelete))
        let ret = keyButton(title: "return", action: #selector(handleReturn))

        let stack = UIStackView(arrangedSubviews: [globe, space, delete, ret])
        stack.axis = .horizontal
        stack.distribution = .fillProportionally
        stack.spacing = 6
        // Give space the most room.
        space.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return stack
    }

    private func keyButton(title: String, action: Selector) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        b.backgroundColor = .secondarySystemBackground
        b.layer.cornerRadius = 6
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    @objc private func handleGlobe() { advanceToNextInputMode() }
    @objc private func handleSpace() { textDocumentProxy.insertText(" ") }
    @objc private func handleDelete() { textDocumentProxy.deleteBackward() }
    @objc private func handleReturn() { textDocumentProxy.insertText("\n") }
}
