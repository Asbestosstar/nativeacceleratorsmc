package com.asbestosstar.nativeaccelerator.client;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry for localized shader-dialogue footer controls. */
final class LegacyShadersFooterLayout {
    record Rect(int x, int y, int width, int height) {}
    record Group(List<Rect> buttons, int rows, int height) {
        Group { buttons = List.copyOf(buttons); }
    }

    private LegacyShadersFooterLayout() {}

    static int rowCount(int screenWidth, int[] desiredWidths, int gap) {
        return layout(screenWidth, 0, desiredWidths, 1, gap, 0).rows();
    }

    static Group layout(int screenWidth, int top, int[] desiredWidths, int buttonHeight, int gap, int rowGap) {
        int margin = Math.min(12, Math.max(0, screenWidth / 4));
        int available = Math.max(1, screenWidth - margin * 2);
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int currentWidth = 0;
        for (int desired : desiredWidths) {
            int buttonWidth = Math.max(1, Math.min(available, Math.max(56, desired)));
            int nextWidth = current.isEmpty() ? buttonWidth : currentWidth + gap + buttonWidth;
            if (!current.isEmpty() && nextWidth > available) {
                rows.add(current);
                current = new ArrayList<>();
                currentWidth = 0;
                nextWidth = buttonWidth;
            }
            current.add(buttonWidth);
            currentWidth = nextWidth;
        }
        if (!current.isEmpty()) rows.add(current);

        List<Rect> buttons = new ArrayList<>(desiredWidths.length);
        for (int row = 0, index = 0; row < rows.size(); row++) {
            List<Integer> widths = rows.get(row);
            int rowWidth = widths.stream().mapToInt(Integer::intValue).sum() + gap * Math.max(0, widths.size() - 1);
            int x = Math.max(0, (screenWidth - rowWidth) / 2);
            int y = top + row * (buttonHeight + rowGap);
            for (int width : widths) {
                buttons.add(new Rect(x, y, width, buttonHeight));
                x += width + gap;
                index++;
            }
        }
        int height = rows.isEmpty() ? 0 : rows.size() * buttonHeight + (rows.size() - 1) * rowGap;
        return new Group(buttons, rows.size(), height);
    }
}
