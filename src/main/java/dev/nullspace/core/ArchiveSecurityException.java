package dev.nullspace.core;

public class ArchiveSecurityException extends SecurityException implements Translatable {
    private final String key;
    private final Object[] args;

    public ArchiveSecurityException(String key, Object... args) {
        super(Translatable.describe(key, args));
        this.key = key;
        this.args = args.clone();
    }

    @Override public String key() { return key; }
    @Override public Object[] args() { return args.clone(); }
}
