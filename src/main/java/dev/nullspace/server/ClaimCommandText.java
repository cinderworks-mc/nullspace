package dev.nullspace.server;

// the command the starter room chooser runs. preset ids contain a colon, which brigadier's unquoted strings reject,
// so the id is quoted
final class ClaimCommandText {
    private ClaimCommandText() {}

    static String of(String presetId) { return "/nullspace claim \"" + presetId + "\""; }
}
