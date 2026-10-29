package codemap.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders sections within a total character budget. Small sections get all they need; the
 * rest is shared equally among the larger ones (water-filling). Every cut is announced with a
 * {@code TRUNCATED:} line so the LLM knows the answer is partial.
 */
public final class Budget {

    public record Rendered(String text, List<Integer> unitsShown) {
    }

    private Budget() {
    }

    public static Rendered render(String preamble, List<Section> sections, int maxChars, int page) {
        int n = sections.size();
        long[] allocation = new long[n];
        long remaining = Math.max(0, maxChars - preamble.length() - 2);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.comparingLong(i -> sections.get(i).fullSize()));
        int left = n;
        for (int idx : order) {
            long share = remaining / Math.max(1, left);
            allocation[idx] = Math.min(sections.get(idx).fullSize(), share);
            remaining -= allocation[idx];
            left--;
        }

        StringBuilder out = new StringBuilder(preamble).append('\n');
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Section s = sections.get(i);
            StringBuilder part = new StringBuilder();
            for (String line : s.fixed()) {
                part.append(line).append('\n');
            }
            long unitsSize = 0;
            for (String unit : s.units()) {
                unitsSize += unit.length() + 1;
            }
            boolean all = s.hiddenUnits() == 0 && part.length() + unitsSize <= allocation[i];
            int count = 0;
            for (String unit : s.units()) {
                // Leave room for the TRUNCATED line unless everything fits.
                if (!all && part.length() + unit.length() + 1 + 170 > allocation[i]) {
                    break;
                }
                part.append(unit).append('\n');
                count++;
            }
            long hidden = s.units().size() - count + s.hiddenUnits();
            if (hidden > 0) {
                part.append("TRUNCATED: ").append(hidden).append(" more ").append(s.unitName()).append(" in ")
                        .append(s.label());
                part.append(s.pageable()
                        ? ", refine the query (filter, aggregate) or use `codemap run --page " + (page + 1) + "`"
                        : ", ask for a narrower part");
                part.append('\n');
            }
            shown.add(count);
            out.append(part).append('\n');
        }
        String text = out.toString();
        if (text.length() > maxChars) {
            // Fixed lines alone exceeded the budget: hard cut, still announced.
            String note = "\nTRUNCATED: output cut at " + maxChars + " chars, ask fewer queries at once\n";
            text = text.substring(0, Math.max(0, maxChars - note.length())) + note;
        }
        return new Rendered(text, shown);
    }
}
