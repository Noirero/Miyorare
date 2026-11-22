#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"
#include <jni.h>
#include <cstdint>
#include <memory>
#include <string>

namespace {
    // JNI methods and the Discord callback pump run on the activity's main thread.
    std::unique_ptr<discordpp::Client> client;
    std::uint64_t generation = 0;
    int result = 0;

    std::string readUtf8(JNIEnv* env, jbyteArray bytes) {
        std::string value(env->GetArrayLength(bytes), '\0');
        env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                                reinterpret_cast<jbyte*>(value.data()));
        return value;
    }

    discordpp::Activity makeCard(const std::string& title, const std::string& chapter,
                                 const std::string& coverUrl, const std::string& sourceUrl,
                                 std::int64_t startedAtSeconds) {
        discordpp::Activity activity;
        activity.SetType(discordpp::ActivityTypes::Playing);
        activity.SetDetails(title);
        activity.SetState(chapter);

        discordpp::ActivityAssets assets;
        assets.SetLargeImage(coverUrl);
        assets.SetLargeText(title);
        activity.SetAssets(assets);

        discordpp::ActivityTimestamps timestamps;
        timestamps.SetStart(startedAtSeconds);
        activity.SetTimestamps(timestamps);

        discordpp::ActivityButton source;
        source.SetLabel("Baca di sumber");
        source.SetUrl(sourceUrl);
        activity.AddButton(source);
        return activity;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_noirero_miyorare_presenceprototype_PresenceActivity_initialize(
    JNIEnv*, jclass, jlong applicationId
) {
    client = std::make_unique<discordpp::Client>();
    client->SetApplicationId(static_cast<std::uint64_t>(applicationId));
}

extern "C" JNIEXPORT void JNICALL
Java_org_noirero_miyorare_presenceprototype_PresenceActivity_publish(
    JNIEnv* env, jclass, jbyteArray title, jbyteArray chapter, jbyteArray coverUrl,
    jbyteArray sourceUrl, jlong startedAtSeconds
) {
    result = 0;
    const auto request = ++generation;
    client->UpdateRichPresence(
        makeCard(readUtf8(env, title), readUtf8(env, chapter), readUtf8(env, coverUrl),
                 readUtf8(env, sourceUrl), startedAtSeconds),
        [request](discordpp::ClientResult response) {
            if (request == generation) result = response.Successful() ? 1 : -1;
        }
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_org_noirero_miyorare_presenceprototype_PresenceActivity_pump(JNIEnv*, jclass) {
    discordpp::RunCallbacks();
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_noirero_miyorare_presenceprototype_PresenceActivity_clear(JNIEnv*, jclass) {
    ++generation;
    result = 0;
    client->ClearRichPresence();
}

extern "C" JNIEXPORT void JNICALL
Java_org_noirero_miyorare_presenceprototype_PresenceActivity_shutdown(JNIEnv*, jclass) {
    ++generation;
    client.reset();
}
