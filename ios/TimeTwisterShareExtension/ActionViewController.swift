import UIKit
import UniformTypeIdentifiers
import TimeTwisterCore

/// Action Extension entry point. Activated from any host app that exposes selected
/// text via the share sheet (Notes, Mail, Safari, WhatsApp Web, …). The extension
/// reads the text, splices a multi-TZ stamp in place of the detected time, and
/// returns the modified text to the host — same UX shape as Android's
/// ACTION_PROCESS_TEXT, just under a different OS primitive.
///
/// Unlike the keyboard extension this needs no "Allow Full Access" toggle — the
/// extension receives the text directly via NSExtensionContext. Trade-off: hosts
/// must explicitly opt in to receiving modified text back, so the in-place splice
/// only works in apps that support it (most native text editors do; many web
/// containers fall back to "copied to clipboard" behavior).
class ActionViewController: UIViewController {

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        guard
            let item = extensionContext?.inputItems.first as? NSExtensionItem,
            let provider = item.attachments?.first(where: {
                $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier)
            })
        else {
            complete(returning: nil)
            return
        }

        provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { [weak self] data, _ in
            guard let self = self else { return }
            let input = (data as? String) ?? (data as? URL)?.absoluteString ?? ""

            // "Select All" in Notes or Mail is one tap from this extension, and the
            // host hands over whatever the user selected. Refuse a whole document
            // rather than parse it: an action extension that does not return
            // promptly is killed by the watchdog, and the user sees a hang rather
            // than a decision. Returning nil leaves their text untouched.
            guard input.count <= TimeParser.maxInputChars else {
                DispatchQueue.main.async { self.complete(returning: nil) }
                return
            }

            let targets = UserPreferences.shared.targetZones
            let out = TimeConverter.splice(input: input, targets: targets)
            DispatchQueue.main.async {
                self.complete(returning: out)
            }
        }
    }

    private func complete(returning text: String?) {
        guard let text, !text.isEmpty else {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }
        let provider = NSItemProvider(item: text as NSString, typeIdentifier: UTType.plainText.identifier)
        let out = NSExtensionItem()
        out.attachments = [provider]
        extensionContext?.completeRequest(returningItems: [out], completionHandler: nil)
    }
}
