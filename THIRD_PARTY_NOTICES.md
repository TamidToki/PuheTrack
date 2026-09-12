# Third-party components

PuheTrack depends on third-party software and model data. This inventory is for release preparation and is not legal advice. Before public distribution, verify the exact packaged dependency graph and provide every notice/license required by the applicable version.

| Component | Use | Upstream/license reference |
|---|---|---|
| LAME 3.100 / libmp3lame sources | MP3 encoding in `libpuhe_lame.so` | [LAME project](https://lame.sourceforge.io/) — LGPL |
| sherpa-onnx 1.13.7 | Offline speech-recognition Android/JNI runtime | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — Apache-2.0 |
| ONNX Runtime (transitive) | Neural inference | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) — MIT |
| NVIDIA Parakeet TDT 0.6B v3 INT8 conversion | Offline Finnish model files downloaded after install | [model repository](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8), pinned revision in `ModelRepository.kt`; model card states CC-BY-4.0 |
| AndroidX / Jetpack Compose / WorkManager / Security Crypto | Android UI, lifecycle, durable jobs and encrypted settings | [Android Open Source Project](https://source.android.com/docs/setup/about/licenses) / component metadata |
| ML Kit Translate 17.0.3 | On-device Finnish → English | [Google ML Kit terms](https://developers.google.com/ml-kit/terms) |
| OkHttp 4.12.0 | Cancellable HTTPS requests | [square/okhttp](https://github.com/square/okhttp) — Apache-2.0 |
| Kotlin and kotlinx-coroutines 1.8.1 | Language/runtime and coroutines | [JetBrains Kotlin](https://github.com/JetBrains/kotlin) and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Apache-2.0 |

The LAME source used to build the shared library is retained under `app/src/main/cpp/lame/`. A public release should include the exact LGPL text and any required source/relinking offer in the distributed notice surface. Model attribution and Google service terms should also be reflected in the store/legal materials.
