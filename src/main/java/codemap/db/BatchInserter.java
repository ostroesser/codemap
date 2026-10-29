package codemap.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;

/** Batched INSERT into one table. Flushes every {@value #BATCH} rows and on close. */
public final class BatchInserter implements AutoCloseable {

    private static final int BATCH = 1000;

    private final PreparedStatement ps;
    private final int arity;
    private int pending;

    public BatchInserter(Connection conn, String table, String... columns) throws SQLException {
        this("INSERT", conn, table, columns);
    }

    /** For tables keyed on natural keys where the first writer wins. */
    public static BatchInserter insertOrIgnore(Connection conn, String table, String... columns) throws SQLException {
        return new BatchInserter("INSERT OR IGNORE", conn, table, columns);
    }

    private BatchInserter(String verb, Connection conn, String table, String... columns) throws SQLException {
        this.arity = columns.length;
        String placeholders = String.join(", ", Collections.nCopies(columns.length, "?"));
        this.ps = conn.prepareStatement(verb + " INTO " + table + " (" + String.join(", ", columns)
                + ") VALUES (" + placeholders + ")");
    }

    public void add(Object... values) throws SQLException {
        if (values.length != arity) {
            throw new IllegalArgumentException("expected " + arity + " values, got " + values.length);
        }
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            ps.setObject(i + 1, v instanceof Boolean b ? (b ? 1 : 0) : v);
        }
        ps.addBatch();
        if (++pending >= BATCH) {
            flush();
        }
    }

    public void flush() throws SQLException {
        if (pending > 0) {
            ps.executeBatch();
            pending = 0;
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            flush();
        } finally {
            ps.close();
        }
    }
}
