package com.asbestosstar.nativeaccelerator.nativeapi;

public enum Endianness {
    UNKNOWN(0),
    LITTLE(1),
    BIG(2);

    private final int nativeId;

    Endianness(int nativeId) {
        this.nativeId = nativeId;
    }

    public int nativeId() {
        return nativeId;
    }

    public static Endianness fromNative(int value) {
        return switch (value) {
            case 1 -> LITTLE;
            case 2 -> BIG;
            default -> UNKNOWN;
        };
    }
}
