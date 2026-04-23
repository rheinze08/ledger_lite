# Security Policy

## Supported Versions

Ledger Lite is a small open-source project and currently tracks the `main` branch.

## Reporting A Vulnerability

If you find a security issue, do not open a public issue with exploit details.

Preferred reporting path:

1. Open a private security advisory on GitHub if available.
2. If that is not available, open a normal issue with a minimal description and ask to move the discussion private.

Please include:

- the affected version or commit
- the platform you tested on
- clear reproduction steps
- any relevant logs or screenshots, with secrets removed

## Scope Notes

- The app stores notes locally on the device.
- The Ollama endpoint is user-configured and may be on a local network.
- Treat any base URL, model name, or note content as user data.
