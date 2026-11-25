package org.koitharu.kotatsu.scrobbling.discord.sdk;

import android.app.Activity;

public final class DiscordSdkActivity {
    private DiscordSdkActivity() {}

    public static void attach(Activity activity) {
        throw new IllegalStateException("Discord Social SDK is missing from this build");
    }
}
