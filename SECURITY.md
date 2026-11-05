# Security Policy

Noah is a self-custodial Bitcoin wallet, so we take reports that could put
users' funds or privacy at risk seriously.

## Reporting a vulnerability

Please report vulnerabilities privately by email to both maintainers:

- niteshbalusu@gmail.com
- hampus.sjoberg@protonmail.com

Do not open a public GitHub issue, pull request or discussion for a
vulnerability.

Please include:

- what is affected: the iOS app, the Android app or the server, with the app
  version and network (Mainnet, Signet or Regtest) where it applies;
- how to reproduce it, and what an attacker could do with it;
- any logs, screenshots or proof-of-concept code. Never send a recovery phrase,
  private key or other secret, yours or anyone else's.

We will acknowledge your report, keep you updated while we investigate, and
credit you when the fix ships unless you would rather stay anonymous. Please
give us a reasonable time to release a fix before you disclose the issue
publicly.

## Scope

In scope:

- the native apps in `apps/ios` and `apps/android`;
- the Noah server in `server/` and its public API;
- release and build tooling in this repository.

Issues in Bark, the Ark server or other upstream projects should go to those
projects. If one affects Noah users, tell us as well.

## Supported versions

Fixes go into the latest release of each app and into the deployed server.
Please check that an issue still reproduces on the latest release before you
report it.
