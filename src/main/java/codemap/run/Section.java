package codemap.run;

import java.util.List;

/**
 * One block of output (a query result, a source excerpt, an error). {@code fixed} lines are
 * always printed; {@code units} (rows, code lines) are printed while the budget allows.
 * {@code hiddenUnits} are units known to exist but not loaded.
 *
 * @param unitName plural noun for the truncation note, e.g. "rows"
 * @param pageable true if {@code codemap run --page N} can show the rest
 */
public record Section(String label, List<String> fixed, List<String> units, long hiddenUnits, String unitName,
                      boolean pageable) {

    public static Section text(String label, List<String> lines) {
        return new Section(label, lines, List.of(), 0, "lines", false);
    }

    long fullSize() {
        long size = 0;
        for (String s : fixed) {
            size += s.length() + 1;
        }
        for (String s : units) {
            size += s.length() + 1;
        }
        return size + (hiddenUnits > 0 ? 160 : 0);
    }
}
