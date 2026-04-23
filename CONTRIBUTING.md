# Contributing

Thanks for taking a look at Voice Ledger Lite.

## Before You Open A PR

- Keep changes focused and easy to review.
- Do not commit secrets, API keys, keystores, or local debug artifacts.
- If you change app behavior, update the README or in-app copy where it affects setup or use.

## Recommended Workflow

1. Fork or branch from `main`.
2. Make the smallest change that solves the problem.
3. Run the app in Android Studio and verify the affected flow.
4. Describe any device, emulator, or Ollama assumptions in the pull request.

## Release Notes

- The current GitHub workflow produces a debug APK for fast testing.
- If you add a signed release path, document the keystore and GitHub Actions secret setup.
