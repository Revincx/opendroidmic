# RNNoise source provenance

- Upstream: https://github.com/xiph/rnnoise
- Commit: `70f1d256acd4b34a572f999a05c87bf00b67730d`
- Commit date: 2025-02-22
- License: BSD-3-Clause; see `COPYING`.
- Model archive SHA-256: `0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`
- Bundled model: upstream `rnnoise_data_little.c`, converted with upstream
  `src/write_weights.c` to `src/main/assets/rnnoise/rnnoise_little.weights`.
- Bundled weights SHA-256:
  `3f67a34f27e6195cb89e536a5019642965197fd3b178ae26af3f9e9f831e7738`.

The vendored `denoise.c` initializes `RNNModel.file` for buffer-backed models.
This local safety fix prevents reading an uninitialized field during cleanup.
