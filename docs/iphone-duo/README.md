# Native simulator preview

Captured October 7, 2026. These are real native UI captures using temporary in-memory sample wallet data. The QR is not payable; no live payment success is implied. The harness is not included in the app source.

[Download the 25-second edited screen recording](noah-iphone-duo-preview.mp4) (H.264 MP4, 1280 × 960, 30 fps, silent). It combines captures of the two simulator displays and briefly holds the final details frames.

| Screen | Closed | Open |
| --- | --- | --- |
| Home | ![Closed Home](home-closed.png) | ![Open Home](home-open.png) |
| Send | ![Closed Send](send-closed.png) | ![Open Send](send-open.png) |
| Receive | ![Closed Receive](receive-closed.png) | ![Open Receive](receive-open.png) |
| History | ![Closed History](history-closed.png) | ![Open History](history-open.png) |

Android UI regression checks passed on Pixel 9 Pro / Android 16 with the same sample-data approach.

| Send | History |
| --- | --- |
| ![Android Send](android-send.png) | ![Android History](android-history.png) |

See the [engineering report](../iphone_duo_research.md) for exact validation boundaries and remaining release work.
