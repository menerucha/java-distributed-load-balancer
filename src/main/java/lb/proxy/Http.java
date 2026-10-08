package lb.proxy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Tiny HTTP/1.x message-head helpers (start line + headers). */
final class Http {
    private Http() {}

    record Head(String startLine, List<String[]> headers) {
        String get(String name) {
            for (String[] h : headers) if (h[0].equalsIgnoreCase(name)) return h[1];
            return null;
        }
        void remove(String... names) {
            headers.removeIf(h -> { for (String n : names) if (h[0].equalsIgnoreCase(n)) return true; return false; });
        }
        void set(String name, String value) { remove(name); headers.add(new String[]{name, value}); }
        byte[] toBytes() {
            StringBuilder sb = new StringBuilder(startLine).append("\r\n");
            for (String[] h : headers) sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
            return sb.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    /** Reads up to and including the blank line. Returns null on clean EOF before any byte. */
    static Head readHead(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
        int state = 0, b;
        while ((b = in.read()) != -1) {
            bos.write(b);
            if (bos.size() > maxBytes) throw new IOException("header too large");
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return parse(bos.toString(StandardCharsets.ISO_8859_1));
        }
        if (bos.size() == 0) return null;
        throw new EOFException("truncated header");
    }

    static Head parse(String raw) throws IOException {
        String[] lines = raw.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) throw new IOException("empty start line");
        List<String[]> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) break;
            int c = lines[i].indexOf(':');
            if (c <= 0) throw new IOException("malformed header: " + lines[i]);
            headers.add(new String[]{lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim()});
        }
        return new Head(lines[0], headers);
    }

    static void sendSimple(OutputStream out, int code, String reason, String body, String... extra) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            .append("Content-Type: text/plain; charset=utf-8\r\nContent-Length: ").append(b.length).append("\r\nConnection: close\r\n");
        for (int i = 0; i + 1 < extra.length; i += 2) sb.append(extra[i]).append(": ").append(extra[i + 1]).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }
}
