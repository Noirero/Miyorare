cmake_minimum_required(VERSION 3.22.1)
project(MiyorareDiscordSdk LANGUAGES CXX)

find_package(discord_partner_sdk REQUIRED CONFIG)
add_library(miyorare_discord_sdk SHARED presence.cpp)
target_compile_features(miyorare_discord_sdk PRIVATE cxx_std_20)
target_link_libraries(miyorare_discord_sdk PRIVATE discord_partner_sdk::discord_partner_sdk)
