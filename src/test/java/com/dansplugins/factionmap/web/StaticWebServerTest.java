package com.dansplugins.factionmap.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StaticWebServerTest {
    @TempDir
    Path tmp;
    Path root;
    StaticWebServer server;

    @BeforeEach
    void start() throws IOException {
        root = Files.createDirectories(tmp.resolve("web"));
        Files.writeString(root.resolve("index.html"), "<!doctype html><title>x</title>");
        Files.createDirectories(root.resolve("tiles"));
        Files.writeString(root.resolve("tiles/index.json"), "{}");
        Files.writeString(root.resolve(".secret"), "no");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "no");
        Files.writeString(tmp.resolve("outside.txt"), "no");
        server = new StaticWebServer("127.0.0.1", 0, root, n -> { });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    HttpURLConnection request(String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + server.port() + path).toURL().openConnection();
        c.setRequestMethod(method);
        return c;
    }

    @Test
    void servesTheViewerWithSafetyHeaders() throws IOException {
        HttpURLConnection c = request("GET", "/");
        assertEquals(200, c.getResponseCode());
        assertEquals("text/html; charset=utf-8", c.getHeaderField("Content-Type"));
        assertEquals("nosniff", c.getHeaderField("X-Content-Type-Options"));
        assertEquals(StaticWebServer.CSP, c.getHeaderField("Content-Security-Policy"));
    }

    @Test
    void theIndexIsNeverCached() throws IOException {
        HttpURLConnection c = request("GET", "/tiles/index.json");
        assertEquals(200, c.getResponseCode());
        assertEquals("no-store", c.getHeaderField("Cache-Control"));
        assertEquals("application/json", c.getHeaderField("Content-Type"));
    }

    @Test
    void headHasNoBody() throws IOException {
        HttpURLConnection c = request("HEAD", "/index.html");
        assertEquals(200, c.getResponseCode());
        assertEquals("31", c.getHeaderField("Content-Length"));
    }

    @Test
    void onlyGetAndHeadAreAllowed() throws IOException {
        for (String m : new String[]{"POST", "PUT", "DELETE", "OPTIONS"}) {
            HttpURLConnection c = request(m, "/index.html");
            assertEquals(405, c.getResponseCode(), m);
        }
    }

    @Test
    void dotfilesTraversalAndDirectoriesAre404() throws IOException {
        for (String p : new String[]{"/.secret", "/.git/config", "/tiles", "/../outside.txt", "/%2e%2e/outside.txt",
                "/tiles/../../outside.txt", "/missing.js"}) {
            assertEquals(404, request("GET", p).getResponseCode(), p);
        }
    }

    @Test
    void resolveRefusesEverythingOutsideTheRoot() throws IOException {
        Path real = root.toRealPath();
        assertNotNull(StaticWebServer.resolve(real, "/index.html"));
        assertNotNull(StaticWebServer.resolve(real, "/"));
        assertNull(StaticWebServer.resolve(real, "/../outside.txt"));
        assertNull(StaticWebServer.resolve(real, "/tiles//index.json"));
        assertNull(StaticWebServer.resolve(real, "/a\\..\\b"));
        assertNull(StaticWebServer.resolve(real, "relative"));
        assertNull(StaticWebServer.resolve(real, "/tiles/"));
    }

    @Test
    void aSymlinkOutOfTheRootIsRefused() throws IOException {
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), tmp.resolve("outside.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return; // no symlinks on this file system
        }
        assertEquals(404, request("GET", "/link.txt").getResponseCode());
    }
}
