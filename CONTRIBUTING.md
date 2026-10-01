# Contributing

MorpBox is MIT-licensed. Protocol documents in `docs/` are CC0.

## Build

JDK 17, Android SDK platform 36.

```bash
./gradlew :app:testDebugUnitTest :youniversal:testDebugUnitTest :app:assembleDebug
```

Python reference spec:

```bash
cd protocol && pip install -e ".[dev]" && pytest -q
```

## Layout

- `app/` — Android client. Dual-transport logic lives in `MorpSession`.
- `youniversal/` — theme library; do not break WCAG floors (`tools/palette_lab.py`).
- `protocol/` — Python executable spec. If Kotlin and Python disagree, `docs/` wins.

Please keep the app usable with **zero internet**. Every send goes through the outbox.
