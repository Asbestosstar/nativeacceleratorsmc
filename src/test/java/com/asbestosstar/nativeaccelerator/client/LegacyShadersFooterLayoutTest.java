package com.asbestosstar.nativeaccelerator.client;

/** Plain-main geometry checks for localized shader-dialogue controls. */
public final class LegacyShadersFooterLayoutTest {
    public static void main(String[] args) {
        int[] widths = {94, 80, 82, 130, 100, 76, 58, 92};
        for (int screenWidth : new int[]{320, 480, 854, 1920}) {
            for (int screenHeight : new int[]{150, 240, 500, 900}) {
                int footerRows = LegacyShadersFooterLayout.rowCount(screenWidth, widths, 6);
                int footerHeight = footerRows * 18 + Math.max(0, footerRows - 1) * 3;
                LegacyShadersFooterLayout.Group measured = LegacyShadersFooterLayout.layout(
                        screenWidth, 0, widths, 18, 6, 3);
                int top = Math.max(0, screenHeight - footerHeight);
                LegacyShadersFooterLayout.Group group = LegacyShadersFooterLayout.layout(
                        screenWidth, top, widths, 18, 6, 3);
            if (group.buttons().size() != widths.length) throw new AssertionError("lost footer button");
            for (LegacyShadersFooterLayout.Rect rect : group.buttons()) {
                if (rect.x() < 0 || rect.x() + rect.width() > screenWidth) {
                    throw new AssertionError("button outside screen at width " + screenWidth + ": " + rect);
                }
                if (rect.y() < 0 || rect.y() + rect.height() > screenHeight) {
                    throw new AssertionError("button outside screen at height " + screenHeight + ": " + rect);
                }
                if (rect.height() != 18) throw new AssertionError("wrong button height");
            }
            for (int i = 0; i < group.buttons().size(); i++) {
                LegacyShadersFooterLayout.Rect a = group.buttons().get(i);
                for (int j = i + 1; j < group.buttons().size(); j++) {
                    LegacyShadersFooterLayout.Rect b = group.buttons().get(j);
                    if (intersects(a, b)) throw new AssertionError("footer buttons overlap: " + a + " / " + b);
                }
            }
                if (group.height() != group.rows() * 18 + Math.max(0, group.rows() - 1) * 3) {
                    throw new AssertionError("footer height does not match its rows");
                }
                LegacyShadersFooterLayout.Rect first = group.buttons().get(0);
                if (first.y() + group.buttons().get(0).height() > screenHeight) {
                    throw new AssertionError("footer does not fit viewport height " + screenHeight);
                }
            }
        }
        System.out.println("LegacyShadersFooterLayoutTest: PASS");
    }

    private static boolean intersects(LegacyShadersFooterLayout.Rect a, LegacyShadersFooterLayout.Rect b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
                && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }
}
