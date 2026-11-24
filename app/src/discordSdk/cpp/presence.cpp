#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"
#include <jni.h>
#include <cstdint>
#include <memory>
#include <string>

namespace {
    // The application-wide Kotlin coordinator calls the bridge on the main thread.
    std::unique_ptr<discordpp::Client> client;

    std::string readUtf8(JNIEnv* env, jbyteArray bytes) {
        std::string value(env->GetArrayLength(bytes), '\0');
        env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                                reinterpret_cast<jbyte*>(value.data()));
        return value;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_koitharu_kotatsu_scrobbling_discord_sdk_DiscordPresenceNative_initialize(
    JNIEnv*, jobject, jlong applicationId
) {
    client = std::make_unique<discordpp::Client>();
    client->SetApplicationId(static_cast<std::uint64_t>(applicationId));
}

extern "C" JNIEXPORT void JNICALL
Java_org_koitharu_kotatsu_scrobbling_discord_sdk_DiscordPresenceNative_publish(
    JNIEnv* env, jobject, jbyteArray title, jbyteArray chapter, jbyteArray cover,
    jbyteArray coverDescription, jbyteArray icon, jbyteArray iconDescription,
    jbyteArray url, jbyteArray buttonLabel, jlong startedAt
) {
    if (!client) return;
    discordpp::Activity activity;
    activity.SetType(discordpp::ActivityTypes::Watching);
    activity.SetName("Miyorare");
    activity.SetDetails(readUtf8(env, title));
    activity.SetState(readUtf8(env, chapter));

    discordpp::ActivityAssets assets;
    assets.SetLargeImage(readUtf8(env, cover));
    assets.SetLargeText(readUtf8(env, coverDescription));
    assets.SetSmallImage(readUtf8(env, icon));
    assets.SetSmallText(readUtf8(env, iconDescription));
    activity.SetAssets(assets);

    discordpp::ActivityTimestamps timestamps;
    timestamps.SetStart(startedAt);
    activity.SetTimestamps(timestamps);

    if (buttonLabel) {
        discordpp::ActivityButton button;
        button.SetLabel(readUtf8(env, buttonLabel));
        button.SetUrl(readUtf8(env, url));
        activity.AddButton(button);
    }
    client->UpdateRichPresence(std::move(activity), [](discordpp::ClientResult) {});
}

extern "C" JNIEXPORT void JNICALL
Java_org_koitharu_kotatsu_scrobbling_discord_sdk_DiscordPresenceNative_pump(JNIEnv*, jobject) {
    discordpp::RunCallbacks();
}

extern "C" JNIEXPORT void JNICALL
Java_org_koitharu_kotatsu_scrobbling_discord_sdk_DiscordPresenceNative_clear(JNIEnv*, jobject) {
    if (client) client->ClearRichPresence();
}
