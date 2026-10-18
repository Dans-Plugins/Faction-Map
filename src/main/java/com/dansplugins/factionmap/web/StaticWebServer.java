package com.dansplugins.factionmap.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

/**
 * A tiny static file server for the viewer and its tiles, on the JDK's built-in HTTP server.
 *
 * <ul>
 *   <li>GET and HEAD only; anything else is 405.</li>
 *   <li>Paths resolve against one fixed root; traversal, dotfiles (any segment starting with a dot) and
 *       directory listings are refused with 404.</li>
 *   <li>{@code X-Content-Type-Options: nosniff}, a restrictive Content-Security-Policy, and
 *       {@code no-store} on {@code index.json} so a viewer always polls the current one.</li>
 * </ul>
 */
public final class StaticWebServer {
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'";
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json",
            "css", "text/css; charset=utf-8",
            "gz", "application/octet-stream",
            "png", "image/png",
            "svg", "image/svg+xml");

    private final HttpServer server;
    private final ExecutorService executor;
    private final Path root;
    private final LongConsumer bytesServed;

    public StaticWebServer(String host, int port, Path root, LongConsumer bytesServed) throws IOException {
        this.root = root.toRealPath();
        this.bytesServed = bytesServed;
        server = HttpServer.create(new InetSocketAddress(host, port), 32);
        server.createContext("/", this::handle);
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "faction-map-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (!method.equals("GET") && !method.equals("HEAD")) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Path file = resolve(root, ex.getRequestURI().getPath());
            if (file == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = Files.readAllBytes(file);
            String name = file.getFileName().toString();
            String ext = name.substring(name.lastIndexOf('.') + 1);
            ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
            ex.getResponseHeaders().set("Cache-Control", name.equals("index.json") ? "no-store" : "no-cache");
            ex.getResponseHeaders().set("Content-Security-Policy", CSP);
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            if (method.equals("HEAD")) {
                ex.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            bytesServed.accept(body.length);
        } catch (IOException e) {
            // Client went away mid-response, or the file was swapped between checks: nothing to do.
        } finally {
            ex.close();
        }
    }

    /** The regular file a request path names under {@code root}, or null when it must not be served. */
    static Path resolve(Path root, String requestPath) {
        if (requestPath == null || !requestPath.startsWith("/") || requestPath.indexOf('\0') >= 0 || requestPath.contains("\\")) {
            return null;
        }
        String rel = requestPath.endsWith("/") ? requestPath + "index.html" : requestPath;
        rel = rel.substring(1);
        for (String segment : rel.split("/")) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                return null; // dotfiles, "..", "//"
            }
        }
        try {
            Path p = root.resolve(rel).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) {
                return null;
            }
            Path real = p.toRealPath();
            return real.startsWith(root) ? real : null; // no symlinks out of the root
        } catch (InvalidPathException | IOException e) {
            return null;
        }
    }
}
