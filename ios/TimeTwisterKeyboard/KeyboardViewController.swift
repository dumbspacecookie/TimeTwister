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

    /// The text before the cursor, bounded to a window.
    ///
    /// `detect` runs on this on **every keystroke, on the main thread**, and iOS
    /// makes no promise about how much context it hands back. Only the most recent
    /// time phrase is ever offered, so everything past the window is work whose
    /// result is discarded.
    ///
    /// Both callers must use this rather than the proxy directly: `apply` computes
    /// offsets against the same string `refreshSuggestions` detected in, so if one
    /// clamped and the other did not, every range would be wrong by the length of
    /// what was trimmed — and the keyboard would delete the wrong characters.
    private func contextBefore() -> String {
        let raw = textDocumentProxy.documentContextBeforeInput ?? ""
        guard raw.count > Self.contextWindow else { return raw }
        return String(raw.suffix(Self.contextWindow))
    }

    /// Generous next to any real time phrase, small next to a document.
    private static let contextWindow = 512

    private func refreshSuggestions() {
        let before = contextBefore()
        let after = textDocumentProxy.documentContextAfterInput ?? ""
        let context = before + after
        guard context != lastSeenContext else { return }
        lastSeenContext = context

        // We look at the most recent time phrase before the cursor —
        // that's what the user just typed and is most likely to want converted.
        //
        // A time inside a spring-forward DST gap is dropped rather than offered:
        // `renderStamp` applies no such check, so suggesting it would put a time
        // the user did not type on the bar and one tap away from their message.
        let candidate = TimeParser.detect(in: before).last
            .flatMap { TimeConverter.isUnrepresentable($0) ? nil : $0 }
        let targets = UserPreferences.shared.targetZones
        suggestionBar.setSuggestion(
            detected: candidate,
            targets: targets
        )
    }

    private func apply(detected: DetectedTime) {
        let targets = UserPreferences.shared.targetZones
        let stamp = TimeConverter.renderStamp(for: detected, targets: targets)

        // Delete the original phrase from the document. documentContextBeforeInput
        // gives us the text ending at the cursor, so we know how far back to
        // delete.
        // Same window refreshSuggestions detected in — see contextBefore().
        let before = contextBefore()
        let nsBefore = before as NSString
        let tail = nsBefore.length - detected.range.location - detected.range.length
        if tail == 0 {
            // The detected phrase sits right at the cursor. Delete it.
            //
            // The loop count must be in GRAPHEMES, not UTF-16 units:
            // `deleteBackward()` removes one user-perceived character, while
            // `detected.range.length` counts code units. Any emoji, flag, accented
            // letter or non-Latin cluster inside the phrase made the two disagree,
            // and the loop then ate that many extra characters of whatever the user
            // had written before it. An astral emoji counts 2, a flag 4, a
            // ZWJ family sequence up to 11.
            let phrase = (before as NSString).substring(with: detected.range)
            for _ in 0..<phrase.count {
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
