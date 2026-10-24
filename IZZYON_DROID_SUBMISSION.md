# IzzyOnDroid / F-Droid Inclusion Guide

To submit PostSweeper to the IzzyOnDroid open-source repository so users can install and auto-update it:

1. Go to: [IzzyOnDroid Inclusion Request](https://gitlab.com/IzzyOnDroid/repo/-/issues/new?issue%5Bissue_type%5D=issue)
2. Title: `[APP]: PostSweeper (app.sweeper)`
3. Fill in the template with these details:

```yaml
Name: PostSweeper
Package: app.sweeper
Repository: https://github.com/abusulayman710/post-sweeper
License: MIT
Category: Privacy / Internet
Summary: Autonomously sweep your X posts, replies, reposts & likes with rate pacing.
Description: |
  PostSweeper is an open-source Android privacy tool that autonomously cleans up
  your own X (Twitter) profile directly from your mobile device.
  Features independent read verification, adaptive rate limiting, dry-run preview,
  fine-grained filters, official archive import, and zero third-party telemetry.
APK Download URL: https://github.com/abusulayman710/post-sweeper/releases/download/%v/PostSweeper-%v.apk
Release Tag Pattern: v*
Fastlane Metadata: Included in repo at fastlane/metadata/android/en-US
```
