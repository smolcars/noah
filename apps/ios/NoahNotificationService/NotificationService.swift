import UserNotifications

/// Names an LNURL-pay payer in Noah's payment notifications (docs/lnurl_pay.md). iOS runs this for every visible push
/// marked `mutable-content`, also after the user force-quit Noah; anything it doesn't recognise, or can't open, shows as
/// it came. The work is local and quick, so the content handler is called right away.
final class NotificationService: UNNotificationServiceExtension {
    override func didReceive(_ request: UNNotificationRequest,
                             // swiftlint:disable:next unneeded_escaping - the superclass's signature
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        let variant = AppVariant.current
        let content = LnurlPayPush.rewritten(request.content, key: NotificationKey(appGroup: variant.appGroup)?.current(),
                                             group: UserDefaults(suiteName: variant.appGroup), variant: variant)
        contentHandler(content ?? request.content)
    }
}
