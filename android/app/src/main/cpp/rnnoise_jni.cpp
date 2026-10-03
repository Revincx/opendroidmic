#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <vector>

#include "rnnoise.h"

namespace {

constexpr int kFrameSize = 480;
constexpr const char *kModelAsset = "rnnoise/rnnoise_little.weights";

struct RnnoiseHandle {
    std::vector<std::uint8_t> model_data;
    RNNModel *model = nullptr;
    DenoiseState *state = nullptr;
    std::array<float, kFrameSize> float_frame{};

    ~RnnoiseHandle() {
        if (state != nullptr) rnnoise_destroy(state);
        if (model != nullptr) rnnoise_model_free(model);
    }
};

void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) env->ThrowNew(exception_class, message);
}

bool load_model(AAssetManager *manager, std::vector<std::uint8_t> *output) {
    AAsset *asset = AAssetManager_open(manager, kModelAsset, AASSET_MODE_STREAMING);
    if (asset == nullptr) return false;

    const off_t length = AAsset_getLength(asset);
    if (length <= 0 || length > std::numeric_limits<int>::max()) {
        AAsset_close(asset);
        return false;
    }

    output->resize(static_cast<std::size_t>(length));
    std::size_t total = 0;
    while (total < output->size()) {
        const int read = AAsset_read(
            asset,
            output->data() + total,
            output->size() - total
        );
        if (read <= 0) {
            AAsset_close(asset);
            return false;
        }
        total += static_cast<std::size_t>(read);
    }
    AAsset_close(asset);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_opendroidmic_RnnoiseNative_nativeCreate(
    JNIEnv *env,
    jclass,
    jobject java_asset_manager
) {
    if (java_asset_manager == nullptr) {
        throw_illegal_state(env, "AssetManager is required for RNNoise");
        return 0;
    }

    AAssetManager *asset_manager = AAssetManager_fromJava(env, java_asset_manager);
    if (asset_manager == nullptr) {
        throw_illegal_state(env, "Could not access Android AssetManager");
        return 0;
    }

    try {
        auto handle = std::make_unique<RnnoiseHandle>();
        if (!load_model(asset_manager, &handle->model_data)) {
            throw_illegal_state(env, "Could not load the bundled RNNoise model");
            return 0;
        }

        handle->model = rnnoise_model_from_buffer(
            handle->model_data.data(),
            static_cast<int>(handle->model_data.size())
        );
        if (handle->model == nullptr) {
            throw_illegal_state(env, "Could not parse the bundled RNNoise model");
            return 0;
        }

        handle->state = rnnoise_create(handle->model);
        if (handle->state == nullptr || rnnoise_get_frame_size() != kFrameSize) {
            throw_illegal_state(env, "Could not initialize RNNoise");
            return 0;
        }
        return reinterpret_cast<jlong>(handle.release());
    } catch (const std::bad_alloc &) {
        throw_illegal_state(env, "Not enough memory to initialize RNNoise");
        return 0;
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_opendroidmic_RnnoiseNative_nativeProcess(
    JNIEnv *env,
    jclass,
    jlong native_handle,
    jshortArray samples,
    jint offset
) {
    auto *handle = reinterpret_cast<RnnoiseHandle *>(native_handle);
    if (handle == nullptr || samples == nullptr) {
        throw_illegal_state(env, "RNNoise is not initialized");
        return 0.0F;
    }

    const jsize length = env->GetArrayLength(samples);
    if (offset < 0 || offset > length - kFrameSize) {
        throw_illegal_state(env, "RNNoise requires exactly 480 available samples");
        return 0.0F;
    }

    std::array<jshort, kFrameSize> pcm{};
    env->GetShortArrayRegion(samples, offset, kFrameSize, pcm.data());
    if (env->ExceptionCheck()) return 0.0F;

    for (int i = 0; i < kFrameSize; ++i) {
        handle->float_frame[i] = static_cast<float>(pcm[i]);
    }

    float speech_probability = rnnoise_process_frame(
        handle->state,
        handle->float_frame.data(),
        handle->float_frame.data()
    );

    for (int i = 0; i < kFrameSize; ++i) {
        const float value = std::isfinite(handle->float_frame[i])
            ? handle->float_frame[i]
            : 0.0F;
        const long rounded = std::lround(value);
        pcm[i] = static_cast<jshort>(std::clamp(
            rounded,
            static_cast<long>(std::numeric_limits<jshort>::min()),
            static_cast<long>(std::numeric_limits<jshort>::max())
        ));
    }
    env->SetShortArrayRegion(samples, offset, kFrameSize, pcm.data());
    if (env->ExceptionCheck()) return 0.0F;

    if (!std::isfinite(speech_probability)) speech_probability = 0.0F;
    return std::clamp(speech_probability, 0.0F, 1.0F);
}

extern "C" JNIEXPORT void JNICALL
Java_com_opendroidmic_RnnoiseNative_nativeDestroy(
    JNIEnv *,
    jclass,
    jlong native_handle
) {
    delete reinterpret_cast<RnnoiseHandle *>(native_handle);
}
