package com.herrylok.tvcast.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class RendererServerTest {
    @Test public void acceptsChunkedSoapButRejectsAmbiguousOrOversizedBodies() throws Exception {
        try (RendererServer server = new RendererServer("127.0.0.1", "uuid:test", "Test TV", new UpnpTest.Fake(), text -> {})) {
            server.start(false);
            String body = UpnpTest.soap(Upnp.AV, "GetTransportInfo", UpnpTest.id());
            String base = "POST /AVTransport/control HTTP/1.1\r\nHost: 127.0.0.1\r\nSOAPAction: \"" + Upnp.type(Upnp.AV) + "#GetTransportInfo\"\r\n";
            String response = raw(server, base + "Transfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n");
            assertTrue(response, response.startsWith("HTTP/1.1 200")); assertTrue(response.contains("NO_MEDIA_PRESENT"));
            assertTrue(raw(server, base + "Content-Length: 999999\r\n\r\n").startsWith("HTTP/1.1 400"));
            assertTrue(raw(server, base + "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n").startsWith("HTTP/1.1 400"));
        }
    }
    private String raw(RendererServer server, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", new URI(server.location()).getPort())) {
            socket.setSoTimeout(3000); socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test public void controllerCanDiscoverDescriptionLoadPlayAndQueryOverHttp() throws Exception {
        UpnpTest.Fake player = new UpnpTest.Fake();
        try (RendererServer server = new RendererServer("127.0.0.1", "uuid:test", "Test TV", player, text -> {})) {
            server.start(false);
            String base = server.location().replace("/description.xml", "");
            assertTrue(get(server.location()).contains("Test TV"));
            assertTrue(get(base + "/AVTransport/scpd.xml").contains("SetAVTransportURI"));
            post(base, "SetAVTransportURI", UpnpTest.id() + "<CurrentURI>https://example.test/test.mp4</CurrentURI><CurrentURIMetaData/>", 200);
            post(base, "Play", UpnpTest.id() + "<Speed>1</Speed>", 200);
            assertTrue(post(base, "GetTransportInfo", UpnpTest.id(), 200).contains("<CurrentTransportState>PLAYING</CurrentTransportState>"));
            assertTrue(post(base, "Seek", UpnpTest.id() + "<Unit>TRACK_NR</Unit><Target>1</Target>", 500).contains("<errorCode>710</errorCode>"));
        }
    }
    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(3000);
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        finally { c.disconnect(); }
    }
    private String post(String base, String action, String args, int expected) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/AVTransport/control").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(3000); c.setReadTimeout(3000);
        c.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
        c.setRequestProperty("SOAPAction", "\"" + Upnp.type(Upnp.AV) + "#" + action + "\"");
        byte[] data = UpnpTest.soap(Upnp.AV, action, args).getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = c.getOutputStream()) { out.write(data); }
        try {
            assertEquals(expected, c.getResponseCode());
            try (InputStream in = expected == 200 ? c.getInputStream() : c.getErrorStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        } finally { c.disconnect(); }
    }
    @Test public void subscriptionReceivesInitialAndChangedStateWithSequence() throws Exception {
        UpnpTest.Fake player = new UpnpTest.Fake();
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        ExecutorService callbacks = Executors.newSingleThreadExecutor();
        try (ServerSocket sink = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
             RendererServer server = new RendererServer("127.0.0.1", "uuid:test", "Test", player, text -> {})) {
            sink.setSoTimeout(5000);
            callbacks.submit(() -> {
                try {
                    for (int i = 0; i < 2; i++) try (Socket s = sink.accept()) {
                        s.setSoTimeout(3000);
                        ByteArrayOutputStream header = new ByteArrayOutputStream(); int b;
                        while ((b = s.getInputStream().read()) != -1) {
                            header.write(b); if (header.toString("UTF-8").endsWith("\r\n\r\n")) break;
                        }
                        String h = header.toString("UTF-8"); int len = 0;
                        for (String line : h.split("\r\n")) if (line.startsWith("CONTENT-LENGTH:")) len = Integer.parseInt(line.substring(15).trim());
                        byte[] body = s.getInputStream().readNBytes(len);
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        events.offer(h + new String(body, StandardCharsets.UTF_8));
                    }
                } catch (Exception e) { events.offer("ERROR: " + e); }
            });
            server.start(false); URI address = new URI(server.location());
            try (Socket subscribe = new Socket("127.0.0.1", address.getPort())) {
                subscribe.setSoTimeout(3000);
                String req = "SUBSCRIBE /AVTransport/event HTTP/1.1\r\nHost: 127.0.0.1\r\nCALLBACK: <http://127.0.0.1:" + sink.getLocalPort() + "/events>\r\nNT: upnp:event\r\nTIMEOUT: Second-1800\r\nConnection: close\r\n\r\n";
                subscribe.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
                String response = new String(subscribe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response, response.startsWith("HTTP/1.1 200"));
                assertTrue(response.toLowerCase().contains("sid: uuid:"));
            }
            String first = events.poll(5, TimeUnit.SECONDS); assertNotNull(first); assertTrue(first, first.contains("SEQ: 0")); assertTrue(first.contains("NO_MEDIA_PRESENT"));
            player.load("http://example.test/video.mp4", ""); player.play();
            String second = events.poll(5, TimeUnit.SECONDS); assertNotNull(second); assertTrue(second, second.contains("SEQ: 1")); assertTrue(second.contains("PLAYING"));
        } finally { callbacks.shutdownNow(); }
    }
}
