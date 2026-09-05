package com.asbestosstar.nativeaccelerator.platform;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record Platform(String os, String arch) {
    public static Platform current() {
        return new Platform(normalizeOs(System.getProperty("os.name", "unknown")),
                normalizeArch(System.getProperty("os.arch", "unknown")));
    }

    /** OS + architecture, without a byte-order suffix. */
    public String id() {
        return os + "-" + arch;
    }

    /**
     * Canonical native-binary resource ID.
     *
     * Fixed-endian architecture IDs such as amd64 and ia64 stay compact.  An
     * endian suffix is only added where the same architecture ID can represent
     * binaries for more than one byte order (for example ppc32 or sparcv9).
     */
    public String nativeId() {
        return usesEndianQualifiedResourceId() ? id() + "-" + endianTag() : id();
    }

    /**
     * Search the canonical path first and then transitional aliases.  This lets
     * jars built by the previous starter (which used -le/-be universally) keep
     * working while new fixed-endian binaries use the shorter canonical name.
     */
    public List<String> nativeResourceIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(nativeId());
        ids.add(id() + "-" + endianTag());
        ids.add(id());
        return List.copyOf(new ArrayList<>(ids));
    }

    public boolean usesEndianQualifiedResourceId() {
        return switch (arch) {
            case "sparcv9", "ppc32", "ppc64", "arm64" -> true;
            default -> false;
        };
    }

    public String endianTag() {
        return ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? "be" : "le";
    }

    public boolean isSolarisSparc() {
        return os.equals("solaris") && arch.equals("sparcv9");
    }

    public static String mappedLibraryName(String baseName) {
        return System.mapLibraryName(baseName);
    }

    private static String normalizeOs(String value) {
        String s = value.toLowerCase(Locale.ROOT);
        if (s.contains("sunos") || s.contains("solaris") || s.contains("illumos")) return "solaris";
        if (s.contains("linux")) return "linux";
        if (s.contains("freebsd")) return "freebsd";
        if (s.contains("netbsd")) return "netbsd";
        if (s.contains("openbsd")) return "openbsd";
        if (s.contains("mac") || s.contains("darwin")) return "macos";
        if (s.contains("haiku")) return "haiku";
        if (s.contains("windows")) return "windows";
        if (s.contains("aix")) return "aix";
        if (s.contains("hp-ux") || s.contains("hpux")) return "hpux";
        if (s.contains("openvms") || s.equals("vms")) return "openvms";
        return s.replaceAll("[^a-z0-9]+", "-");
    }

    private static String normalizeArch(String value) {
        String s = value.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "amd64", "x86_64", "x86-64" -> "amd64";
            case "sparcv9", "sparc64", "sparc" -> "sparcv9";
            case "aarch64", "arm64" -> "arm64";
            case "ppc64le", "powerpc64le" -> "ppc64le";
            case "ppc64", "powerpc64" -> "ppc64";
            case "ppc", "powerpc", "ppc32", "powerpc32" -> "ppc32";
            case "ia64", "itanium" -> "ia64";
            default -> s.replaceAll("[^a-z0-9]+", "-");
        };
    }
}
