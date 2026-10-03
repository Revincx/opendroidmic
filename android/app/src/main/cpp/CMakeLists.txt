cmake_minimum_required(VERSION 3.22.1)
project(opendroidmic_audio LANGUAGES C CXX)

set(RNNOISE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/rnnoise")

add_library(rnnoise STATIC
    "${RNNOISE_DIR}/src/denoise.c"
    "${RNNOISE_DIR}/src/rnn.c"
    "${RNNOISE_DIR}/src/pitch.c"
    "${RNNOISE_DIR}/src/kiss_fft.c"
    "${RNNOISE_DIR}/src/celt_lpc.c"
    "${RNNOISE_DIR}/src/nnet.c"
    "${RNNOISE_DIR}/src/nnet_default.c"
    "${RNNOISE_DIR}/src/parse_lpcnet_weights.c"
    "${RNNOISE_DIR}/src/rnnoise_model.c"
    "${RNNOISE_DIR}/src/rnnoise_tables.c"
)

target_include_directories(rnnoise
    PUBLIC "${RNNOISE_DIR}/include"
    PRIVATE "${RNNOISE_DIR}/src"
)
target_compile_definitions(rnnoise PRIVATE
    RNNOISE_BUILD
    USE_WEIGHTS_FILE
    DISABLE_DEBUG_FLOAT
)
target_compile_features(rnnoise PRIVATE c_std_99)
set_target_properties(rnnoise PROPERTIES POSITION_INDEPENDENT_CODE ON)

# arm64 always has NEON. The Android 8+ 32-bit devices supported by this app
# are built with NEON so RNNoise does not fall back to its slow scalar path.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(rnnoise PRIVATE -mfpu=neon)
endif()

add_library(opendroidmic_audio SHARED rnnoise_jni.cpp)
target_compile_features(opendroidmic_audio PRIVATE cxx_std_17)
target_compile_options(opendroidmic_audio PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)
target_link_libraries(opendroidmic_audio
    PRIVATE
    rnnoise
    android
    log
    m
)
