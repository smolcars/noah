# Noah Wallet Privacy Policy

**Apps Covered:** Noah, Noah (Signet)  
**Developer:** Hampus Sjöberg  
**Privacy Contact:** hampus.sjoberg at protonmail.com  
**Last Updated: 2026-11-27**

## Our Commitment to Privacy

Noah Wallet is built with privacy as a core principle. We believe your financial data should remain private and under your control.

## Information We Collect

### Error Tracking with Sentry

Noah Wallet uses Sentry, an error tracking and monitoring service, to help us identify and fix bugs in the application.

**What Sentry Collects:**

- Error reports and crash logs to help us diagnose and fix issues
- Application performance data related to errors
- Anonymous device information (device type, OS version) necessary for debugging
- Stack traces and error context to understand what went wrong

**What Sentry Does NOT Collect:**

- Personal information such as your name or email address
- Transaction details or wallet balances
- Private keys or seed phrases
- Wallet addresses or payment information
- Sensitive financial data

Sentry's data collection is configured with privacy in mind. We use Sentry's data scrubbing features to minimize the collection of sensitive information. You can learn more about Sentry's privacy practices at [Sentry's Privacy Policy](https://sentry.io/privacy/).

### Lightning Address Payments

When someone pays your Noah Lightning address, their wallet can include a name, their own Lightning address and a comment. The payer chooses what to send, and Noah does not verify it. Noah's server:

- Stores it encrypted until your app has saved it with the payment, for at most seven days. If the payment doesn't arrive within an hour, it is deleted.
- Sends it to your phone inside the payment notification, encrypted with a key only your app holds, so Apple, Google and push services can't read it.
- Keeps it out of its logs and out of Sentry.

Your app saves it with the payment in your wallet, and so in your wallet backup, which is encrypted with your recovery phrase.

When you pay a Lightning address or another LNURL-pay service that asks for them, Noah sends your name (if you set one) and your Noah Lightning address with the payment. Noah tells you before you confirm the payment, or when you set up a recurring payment.

## Information We Don't Collect

- **No User Analytics**: We do not implement user behavior tracking or analytics services such as Firebase Analytics
- **No Personal Data**: We do not collect, store, or transmit personal information, transaction data, or wallet information to our servers beyond what is necessary for error tracking and the Lightning address payment details described above
- **No Third-Party Analytics**: We do not use third-party analytics services to monitor your app usage patterns

## Information Collected by App Stores

While we don't collect data ourselves beyond error tracking, the platforms that distribute our app may collect limited information:

- **Google Play Store**: May collect basic app usage statistics and crash reports as part of their standard distribution service
- **Apple TestFlight/App Store**: May collect basic app usage statistics and crash reports as part of their standard distribution service

This data collection is controlled by Google and Apple respectively, not by Noah Wallet. Please refer to their privacy policies for more information about their data practices.

## Data Storage

- All wallet data is stored locally on your device
- We do not maintain servers that store user wallet data
- Your Bitcoin transactions are recorded on the Bitcoin blockchain, which is public by design
- Ark transactions are processed through the Ark protocol network, which operates on top of Bitcoin

---

_This policy reflects our commitment to user privacy while maintaining the quality and reliability of the Noah Wallet application through responsible error tracking._
