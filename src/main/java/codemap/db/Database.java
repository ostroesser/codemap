package codemap.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.sqlite.SQLiteConfig;

/** Opens the codemap SQLite database and creates the schema. */
public final class Database {

    /** Bumped whenever schema.sql changes incompatibly; stored in PRAGMA user_version. */
    public static final int SCHEMA_VERSION = 1;

    private Database() {
    }

    /** Opens (creating if needed) a writable database with the current schema. */
    public static Connection openForWrite(Path dbFile) throws SQLException {
        createParentDirs(dbFile);
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(false);
        Connection conn = config.createConnection("jdbc:sqlite:" + dbFile);
        int version = userVersion(conn);
        if (version == 0) {
            createSchema(conn);
        } else if (version != SCHEMA_VERSION) {
            conn.close();
            throw new SQLException("Database " + dbFile + " has schema version " + version
                    + ", this codemap expects " + SCHEMA_VERSION + ". Delete it and rescan.");
        }
        return conn;
    }

    /** Opens an existing database read-only; used to run LLM-written SQL safely. */
    public static Connection openReadOnly(Path dbFile) throws SQLException {
        if (!Files.exists(dbFile)) {
            throw new SQLException("Database not found: " + dbFile + " (run `codemap scan` first)");
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Connection conn = config.createConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA query_only = ON");
        }
        return conn;
    }

    static void createSchema(Connection conn) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String sql : schemaStatements()) {
                st.execute(sql);
            }
            SchemaDoc.load().insertInto(conn);
            st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /** The DDL statements of schema.sql, split on lines containing only ';;'. */
    public static List<String> schemaStatements() {
        String text = readResource("/codemap/schema.sql");
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.strip().equals(";;")) {
                addStatement(statements, current);
                current.setLength(0);
            } else if (!line.strip().startsWith("--")) {
                current.append(line).append('\n');
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder sql) {
        String s = sql.toString().strip();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (!s.isEmpty()) {
            statements.add(s);
        }
    }

    private static int userVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement(); var rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void createParentDirs(Path dbFile) {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static String readResource(String name) {
        try (InputStream in = Database.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
