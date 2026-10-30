package com.herrylok.tvcast.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bounded HTTP/1 server supporting UPnP's SUBSCRIBE/UNSUBSCRIBE methods. */
abstract class LocalHttp {
    enum Method { GET, HEAD, POST, SUBSCRIBE, UNSUBSCRIBE, OTHER }
    static final class Response {
        enum Status {
            OK(200, "OK"), BAD_REQUEST(400, "Bad Request"), NOT_FOUND(404, "Not Found"),
            METHOD_NOT_ALLOWED(405, "Method Not Allowed"), PRECONDITION_FAILED(412, "Precondition Failed"), INTERNAL_ERROR(500, "Internal Server Error");
            final int code; final String reason;
            Status(int code, String reason) { this.code = code; this.reason = reason; }
        }
        final Status status; final String type, body; final Map<String, String> headers = new LinkedHashMap<>();
        Response(Status status, String type, String body) { this.status = status; this.type = type; this.body = body; }
        void addHeader(String name, String value) { headers.put(name, value); }
    }
    static final class Request {
        final String path, peer; final Method method; final Map<String, String> headers; final byte[] body;
        Request(String path, String peer, Method method, Map<String, String> headers, byte[] body) {
            this.path = path; this.peer = peer; this.method = method; this.headers = headers; this.body = body;
        }
        String getUri() { return path; }
        String getRemoteIpAddress() { return peer; }
        Method getMethod() { return method; }
        Map<String, String> getHeaders() { return headers; }
        InputStream getInputStream() { return new ByteArrayInputStream(body); }
    }
    private final String host; private final int port;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(12), r -> { Thread t = new Thread(r, "cast-http"); t.setDaemon(true); return t; });
    private ServerSocket server;
    private volatile boolean closed;
    LocalHttp(String host, int port) { this.host = host; this.port = port; }
    abstract Response serve(Request request);
    static Response newFixedLengthResponse(Response.Status status, String type, String body) { return new Response(status, type, body); }
    int getListeningPort() { return server.getLocalPort(); }
    void start(int timeout, boolean daemon) throws IOException {
        server = new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress(host, port), 16);
        Thread accept = new Thread(() -> {
            while (!closed) try {
                Socket socket = server.accept(); socket.setSoTimeout(timeout); clients.add(socket);
                try { workers.execute(() -> handle(socket)); }
                catch (RejectedExecutionException e) { clients.remove(socket); socket.close(); }
            } catch (IOException e) { if (!closed) stop(); }
        }, "cast-http-accept");
        accept.setDaemon(daemon); accept.start();
    }
    void stop() {
        closed = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Socket s : clients) try { s.close(); } catch (IOException ignored) { }
        clients.clear(); workers.shutdownNow();
    }
    private void handle(Socket socket) {
        try (Socket s = socket) {
            Response response;
            try { response = serve(read(s)); }
            catch (IllegalArgumentException | IOException e) { response = new Response(Response.Status.BAD_REQUEST, "text/plain", "Malformed HTTP request"); }
            catch (Exception e) { response = new Response(Response.Status.INTERNAL_ERROR, "text/plain", "Request failed"); }
            byte[] body = response.body.getBytes(StandardCharsets.UTF_8);
            StringBuilder headers = new StringBuilder("HTTP/1.1 " + response.status.code + " " + response.status.reason + "\r\nContent-Type: " + response.type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n");
            for (Map.Entry<String, String> h : response.headers.entrySet()) headers.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            OutputStream out = s.getOutputStream(); out.write(headers.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
        } catch (IOException ignored) { } finally { clients.remove(socket); }
    }
    private Request read(Socket socket) throws IOException {
        InputStream input = new BufferedInputStream(socket.getInputStream());
        String[] start = line(input).split(" ");
        if (start.length != 3 || !start[1].startsWith("/") || !(start[2].equals("HTTP/1.1") || start[2].equals("HTTP/1.0"))) throw new IOException();
        Method method;
        try { method = Method.valueOf(start[0]); } catch (IllegalArgumentException e) { method = Method.OTHER; }
        Map<String, String> headers = new HashMap<>();
        int headerBytes = 0;
        while (true) {
            String line = line(input); if (line.isEmpty()) break;
            headerBytes += line.length(); if (headerBytes > 16384) throw new IOException();
            int colon = line.indexOf(':'); if (colon <= 0 || Character.isWhitespace(line.charAt(0))) throw new IOException();
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+") || headers.containsKey(name)) throw new IOException();
            headers.put(name, line.substring(colon + 1).trim());
        }
        if (headers.containsKey("expect")) throw new IOException("Expect unsupported");
        byte[] body;
        if (headers.containsKey("transfer-encoding")) {
            if (headers.containsKey("content-length") || !headers.get("transfer-encoding").equalsIgnoreCase("chunked")) throw new IOException();
            ByteArrayOutputStream chunks = new ByteArrayOutputStream();
            while (true) {
                String length = line(input).split(";", 2)[0];
                if (!length.matches("[0-9a-fA-F]{1,6}")) throw new IOException();
                int count = Integer.parseInt(length, 16);
                if (count == 0) {
                    int trailers = 0; String trailer;
                    while (!(trailer = line(input)).isEmpty()) { trailers += trailer.length(); if (trailers > 8192) throw new IOException(); }
                    break;
                }
                if (chunks.size() + count > 65536) throw new IOException();
                chunks.write(bytes(input, count)); if (!line(input).isEmpty()) throw new IOException();
            }
            body = chunks.toByteArray();
        } else {
            String length = headers.getOrDefault("content-length", "0");
            if (!length.matches("[0-9]{1,6}")) throw new IOException();
            int count = Integer.parseInt(length); if (count > 65536) throw new IOException();
            body = bytes(input, count);
        }
        headers.put("content-length", Integer.toString(body.length));
        return new Request(start[1].split("\\?", 2)[0], socket.getInetAddress().getHostAddress(), method, headers, body);
    }
    private static byte[] bytes(InputStream input, int length) throws IOException {
        byte[] data = new byte[length]; int at = 0;
        while (at < length) { int count = input.read(data, at, length - at); if (count < 0) throw new EOFException(); at += count; }
        return data;
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (b.size() < 8192) {
            int ch = input.read(); if (ch == -1) throw new EOFException();
            if (ch == '\n') {
                byte[] raw = b.toByteArray();
                if (raw.length == 0 || raw[raw.length - 1] != '\r') throw new IOException();
                return new String(raw, 0, raw.length - 1, StandardCharsets.US_ASCII);
            }
            if (ch == 0) throw new IOException(); b.write(ch);
        }
        throw new IOException("Line too long");
    }
}
