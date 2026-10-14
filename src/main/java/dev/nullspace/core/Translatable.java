package dev.nullspace.core;

import java.util.Arrays;

public interface Translatable {
    String key();
    Object[] args();

    // "this server has switched x off" is one message. the key tail names the feature, Msg turns the pair into the one lang key
    String DISABLED = "message.nullspace.disabled";

    static String disabled(String feature) { return DISABLED + "." + feature; }

    static String describe(String key, Object... args) { return args.length == 0 ? key : key + Arrays.toString(args); }
}
