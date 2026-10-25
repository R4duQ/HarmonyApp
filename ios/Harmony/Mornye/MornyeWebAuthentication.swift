import AuthenticationServices
import UIKit

@MainActor
final class MornyeWebAuthentication: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = MornyeWebAuthentication()
    private var session: ASWebAuthenticationSession?
    private var anchor: ASPresentationAnchor?

    func open(_ url: URL, completion: @escaping (URL?, String?) -> Void) {
        guard session == nil else { return }
        guard url.scheme?.lowercased() == "https",
              let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                .flatMap(\.windows).first(where: \.isKeyWindow) else {
            completion(nil, "Open Harmony before starting provider verification.")
            return
        }
        anchor = window
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "mornye") { [weak self] url, error in
            Task { @MainActor in
                self?.session = nil
                self?.anchor = nil
                if (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin {
                    completion(nil, nil)
                } else {
                    completion(url, error.map { MornyeJSON.redact($0.localizedDescription) })
                }
            }
        }
        session.presentationContextProvider = self
        self.session = session
        if !session.start() {
            self.session = nil
            anchor = nil
            completion(nil, "The provider verification browser could not start.")
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor ?? ASPresentationAnchor()
    }
}
