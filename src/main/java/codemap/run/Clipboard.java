package codemap.run;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * System clipboard through the platform's command-line tools. Under WSL and Windows:
 * PowerShell {@code Get-Clipboard} (forced to UTF-8) to read, {@code clip.exe} fed UTF-16LE
 * with a BOM to write, which keeps non-ASCII text intact. macOS: pbpaste/pbcopy.
 * Linux desktops: wl-paste/wl-copy, xclip or xsel.
 */
public final class Clipboard {

    private Clipboard() {
    }

    enum Platform { WINDOWS_OR_WSL, MAC, WAYLAND, X11, NONE }

    static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Platform.WINDOWS_OR_WSL;
        }
        if (os.contains("mac")) {
            return Platform.MAC;
        }
        try {
            String version = Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT);
            if (version.contains("microsoft") || version.contains("wsl")) {
                return Platform.WINDOWS_OR_WSL;
            }
        } catch (IOException ignored) {
            // not Linux, or no /proc
        }
        if (System.getenv("WAYLAND_DISPLAY") != null && onPath("wl-paste")) {
            return Platform.WAYLAND;
        }
        if (System.getenv("DISPLAY") != null && (onPath("xclip") || onPath("xsel"))) {
            return Platform.X11;
        }
        return Platform.NONE;
    }

    public static String read() throws IOException {
        List<String> cmd = switch (detect()) {
            case WINDOWS_OR_WSL -> List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Clipboard -Raw");
            case MAC -> List.of("pbpaste");
            case WAYLAND -> List.of("wl-paste", "--no-newline");
            case X11 -> onPath("xclip") ? List.of("xclip", "-selection", "clipboard", "-o")
                    : List.of("xsel", "--clipboard", "--output");
            case NONE -> throw new IOException("no clipboard tool found; pass a file or '-' for stdin");
        };
        byte[] out = exec(cmd, null);
        String text = new String(out, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    public static void write(String text) throws IOException {
        Platform p = detect();
        switch (p) {
            case WINDOWS_OR_WSL -> {
                ByteBuffer utf16 = StandardCharsets.UTF_16LE.encode(text.replace("\r\n", "\n").replace("\n", "\r\n"));
                byte[] bytes = new byte[utf16.remaining() + 2];
                bytes[0] = (byte) 0xFF;
                bytes[1] = (byte) 0xFE;
                utf16.get(bytes, 2, bytes.length - 2);
                exec(List.of("clip.exe"), bytes);
            }
            case MAC -> exec(List.of("pbcopy"), text.getBytes(StandardCharsets.UTF_8));
            case WAYLAND -> exec(List.of("wl-copy"), text.getBytes(StandardCharsets.UTF_8));
            case X11 -> exec(onPath("xclip") ? List.of("xclip", "-selection", "clipboard")
                    : List.of("xsel", "--clipboard", "--input"), text.getBytes(StandardCharsets.UTF_8));
            case NONE -> throw new IOException("no clipboard tool found; use --out FILE or --stdout");
        }
    }

    private static byte[] exec(List<String> cmd, byte[] input) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (OutputStream in = p.getOutputStream()) {
            if (input != null) {
                in.write(input);
            }
        }
        byte[] out = p.getInputStream().readAllBytes();
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException(cmd.get(0) + " failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return out;
    }

    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, tool))) {
                return true;
            }
        }
        return false;
    }
}
