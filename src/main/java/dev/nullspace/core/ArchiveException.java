package dev.nullspace.core;

// carries a translation key plus arguments so core stays free of minecraft types and english text. still an
// IllegalArgumentException for callers that only care that the request was bad
public class ArchiveException extends IllegalArgumentException implements Translatable {
    public static final String CORRUPT = "error.nullspace.corrupt";

    private final String key;
    private final Object[] args;

    public ArchiveException(String key, Object... args) {
        super(Translatable.describe(key, args));
        this.key = key;
        this.args = args.clone();
    }

    public static ArchiveException corrupt(String code) { return new ArchiveException(CORRUPT, code); }

    @Override public String key() { return key; }
    @Override public Object[] args() { return args.clone(); }
}
