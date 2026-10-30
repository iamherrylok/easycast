package com.herrylok.tvcast.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** A local-network receiver. Its lifetime is explicitly owned by the foreground screen. */
public final class RendererServer implements AutoCloseable {
    private static final String SERVER = "Android/1.0 UPnP/1.0 EasyCast/1.0.0";
    private final String address, uuid, name;
    private final Upnp protocol;
    private final Consumer<String> report;
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger searches = new AtomicInteger();
    private Http http;
    private MulticastSocket ssdp;
    private volatile boolean closed;
    private String location;

    public RendererServer(String address, String uuid, String name, Playback player, Consumer<String> report) {
        this.address = address; this.uuid = uuid; this.name = name;
        this.protocol = new Upnp(player); this.report = report;
    }
    public void start() throws IOException {
        start(true);
    }
    // HTTP/GENA can be exercised over loopback without joining a household multicast group.
    void start(boolean discovery) throws IOException {
        try {
            http = new Http(address); http.start(5000, true);
            location = "http://" + address + ":" + http.getListeningPort() + "/description.xml";
            timer.scheduleWithFixedDelay(this::events, 1, 1, TimeUnit.SECONDS);
            if (!discovery) return;
            ssdp = new MulticastSocket(null);
            ssdp.setReuseAddress(true);
            ssdp.bind(new InetSocketAddress(1900));
            NetworkInterface nic = NetworkInterface.getByInetAddress(InetAddress.getByName(address));
            ssdp.setNetworkInterface(nic); ssdp.setTimeToLive(2);
            ssdp.joinGroup(new InetSocketAddress("239.255.255.250", 1900), nic);
            Thread listener = new Thread(this::listen, "cast-ssdp"); listener.setDaemon(true); listener.start();
            timer.scheduleWithFixedDelay(() -> advertise("ssdp:alive"), 0, 60, TimeUnit.SECONDS);
            report.accept("接收服务已启动，等待手机连接");
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    public String location() { return location; }
    private String[] targets() {
        return new String[]{ "upnp:rootdevice", uuid, Upnp.DEVICE, Upnp.type(Upnp.AV), Upnp.type(Upnp.RC), Upnp.type(Upnp.CM) };
    }
    private String usn(String target) { return target.equals(uuid) ? uuid : uuid + "::" + target; }
    private void send(String text, InetAddress host, int port) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ssdp.send(new DatagramPacket(data, data.length, host, port));
    }
    private void advertise(String kind) {
        if (ssdp == null || ssdp.isClosed()) return;
        for (String target : targets()) try {
            send("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=180\r\nLOCATION: " + location
                    + "\r\nNT: " + target + "\r\nNTS: " + kind + "\r\nSERVER: " + SERVER + "\r\nUSN: " + usn(target) + "\r\n\r\n",
                    InetAddress.getByName("239.255.255.250"), 1900);
        } catch (IOException e) { if (!closed) report.accept("设备广播失败，请重启接收服务"); }
    }
    private void listen() {
        byte[] buffer = new byte[8192];
        while (!closed) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length); ssdp.receive(packet);
                String request = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                Map<String, String> headers = searchHeaders(request);
                if (headers.isEmpty()) continue;
                int mx;
                try { mx = Math.max(1, Math.min(3, Integer.parseInt(headers.getOrDefault("mx", "1")))); }
                catch (NumberFormatException e) { continue; }
                String st = headers.get("st");
                for (String target : targets()) if (target.equals(st) || "ssdp:all".equals(st)) {
                    if (searches.incrementAndGet() > 48) { searches.decrementAndGet(); continue; }
                    InetAddress host = packet.getAddress(); int port = packet.getPort();
                    timer.schedule(() -> {
                        try {
                            if (!closed) send("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=180\r\nEXT:\r\nLOCATION: " + location
                                    + "\r\nSERVER: " + SERVER + "\r\nST: " + target + "\r\nUSN: " + usn(target) + "\r\n\r\n", host, port);
                        } catch (IOException ignored) { } finally { searches.decrementAndGet(); }
                    }, ThreadLocalRandom.current().nextInt(mx * 1000), TimeUnit.MILLISECONDS);
                }
            } catch (IOException | RejectedExecutionException e) {
                if (!closed) report.accept("设备发现服务中断，请重启接收服务");
                break;
            }
        }
    }
    static Map<String, String> searchHeaders(String request) {
        Map<String, String> result = new HashMap<>();
        String[] lines = request.split("\r?\n");
        if (lines.length == 0 || !lines[0].equals("M-SEARCH * HTTP/1.1")) return result;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) result.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        if (!"\"ssdp:discover\"".equalsIgnoreCase(result.get("man")) || !result.containsKey("st")) result.clear();
        return result;
    }
    private final class Http extends LocalHttp {
        Http(String address) { super(address, 0); }
        @Override public Response serve(Request session) {
            String path = session.getUri();
            Method method = session.getMethod();
            try {
                if (method == Method.GET && path.equals("/description.xml")) return response(Response.Status.OK, Upnp.description(uuid, name));
                for (String service : Upnp.SERVICES) {
                    if (method == Method.GET && path.equals("/" + service + "/scpd.xml")) return response(Response.Status.OK, Upnp.scpd(service));
                    if (method == Method.POST && path.equals("/" + service + "/control")) {
                        int length;
                        try { length = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "-1")); }
                        catch (NumberFormatException e) { throw new Upnp.Fault(402, "Invalid Args"); }
                        if (length < 0 || length > 65536) return response(Response.Status.BAD_REQUEST, "Invalid body length");
                        byte[] data = new byte[length]; InputStream input = session.getInputStream();
                        int offset = 0;
                        while (offset < length) { int read = input.read(data, offset, length - offset); if (read < 0) throw new IOException("Incomplete body"); offset += read; }
                        String action = session.getHeaders().getOrDefault("soapaction", "");
                        String body = protocol.control(service, action, new String(data, StandardCharsets.UTF_8));
                        if (action.contains("#SetAVTransportURI")) report.accept("已收到手机的视频，请等待播放");
                        return response(Response.Status.OK, body);
                    }
                    if (path.equals("/" + service + "/event")) return subscription(session, service);
                }
                return response(Response.Status.NOT_FOUND, "Not found");
            } catch (Upnp.Fault e) { return response(Response.Status.INTERNAL_ERROR, e.body()); }
            catch (Exception e) { return response(Response.Status.INTERNAL_ERROR, new Upnp.Fault(501, "Action Failed").body()); }
        }
    }
    private LocalHttp.Response response(LocalHttp.Response.Status status, String body) {
        LocalHttp.Response r = LocalHttp.newFixedLengthResponse(status, "text/xml; charset=\"utf-8\"", body);
        r.addHeader("Server", SERVER); return r;
    }
    private synchronized LocalHttp.Response subscription(LocalHttp.Request request, String service) throws Exception {
        Map<String, String> headers = request.getHeaders();
        String sid = headers.get("sid");
        Subscription sub = sid == null ? null : subscriptions.get(sid);
        String peer = request.getRemoteIpAddress();
        if (sub != null && (!sub.peer.equals(peer) || !sub.service.equals(service) || sub.expires < System.nanoTime())) sub = null;
        if (request.getMethod() == LocalHttp.Method.UNSUBSCRIBE) {
            if (sub == null || headers.containsKey("callback") || headers.containsKey("nt")) return response(LocalHttp.Response.Status.PRECONDITION_FAILED, "Unknown subscription");
            subscriptions.remove(sid); return response(LocalHttp.Response.Status.OK, "");
        }
        if (request.getMethod() != LocalHttp.Method.SUBSCRIBE) return response(LocalHttp.Response.Status.METHOD_NOT_ALLOWED, "Method not allowed");
        if (sid == null) {
            if (subscriptions.size() >= 8 || !"upnp:event".equals(headers.get("nt"))) return response(LocalHttp.Response.Status.PRECONDITION_FAILED, "Invalid subscription");
            URI callback = validateCallback(headers.getOrDefault("callback", ""), peer);
            if (callback == null) return response(LocalHttp.Response.Status.PRECONDITION_FAILED, "Invalid callback");
            sid = "uuid:" + UUID.randomUUID(); sub = new Subscription(service, callback, peer);
            subscriptions.put(sid, sub);
        } else if (sub == null || headers.containsKey("callback") || headers.containsKey("nt")) {
            return response(LocalHttp.Response.Status.PRECONDITION_FAILED, "Unknown subscription");
        }
        sub.expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(1800);
        LocalHttp.Response r = response(LocalHttp.Response.Status.OK, "");
        r.addHeader("SID", sid); r.addHeader("TIMEOUT", "Second-1800");
        // Events start on the next timer tick, after the SUBSCRIBE response is sent.
        return r;
    }
    static URI validateCallback(String value, String peer) {
        try {
            if (!value.startsWith("<") || !value.endsWith(">")) return null;
            URI uri = new URI(value.substring(1, value.length() - 1));
            // Literal sender IPv4 only: prevent DNS rebinding and notifications to unrelated hosts.
            if (!"http".equals(uri.getScheme()) || !peer.equals(uri.getHost()) || !peer.matches("[0-9.]+")
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) return null;
            return uri;
        } catch (Exception e) { return null; }
    }
    private static final class Subscription {
        final String service, peer; final URI callback;
        volatile long expires; long sequence; String previous = "";
        Subscription(String service, URI callback, String peer) {
            this.service = service; this.callback = callback; this.peer = peer;
            this.expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(1800);
        }
    }
    private void events() {
        if (closed) return;
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            Subscription sub = entry.getValue();
            if (sub.expires < System.nanoTime()) { subscriptions.remove(entry.getKey()); continue; }
            try {
                String body = protocol.event(sub.service);
                if (body.equals(sub.previous)) continue;
                notifyEvent(entry.getKey(), sub, body);
                sub.previous = body;
                sub.sequence = sub.sequence == 4294967295L ? 1 : sub.sequence + 1;
            } catch (Exception e) { subscriptions.remove(entry.getKey()); }
        }
    }
    private void notifyEvent(String sid, Subscription sub, String body) throws IOException {
        int port = sub.callback.getPort() == -1 ? 80 : sub.callback.getPort();
        String path = sub.callback.getRawPath(); if (path == null || path.isEmpty()) path = "/";
        if (sub.callback.getRawQuery() != null) path += "?" + sub.callback.getRawQuery();
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(sub.peer, port), 1500); socket.setSoTimeout(1500);
            String header = "NOTIFY " + path + " HTTP/1.1\r\nHOST: " + sub.peer + ":" + port + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " + sid
                    + "\r\nSEQ: " + sub.sequence + "\r\nCONTENT-LENGTH: " + data.length + "\r\nCONNECTION: close\r\n\r\n";
            socket.getOutputStream().write(header.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().write(data); socket.getOutputStream().flush();
            // Read a bounded status line; a failed notification invalidates this subscription.
            StringBuilder status = new StringBuilder(); int ch;
            while (status.length() < 256 && (ch = socket.getInputStream().read()) != -1 && ch != '\n') status.append((char) ch);
            if (!status.toString().trim().matches("HTTP/1\\.[01] 2[0-9][0-9].*")) throw new IOException("Callback rejected notification");
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; advertise("ssdp:byebye");
        if (ssdp != null) ssdp.close();
        if (http != null) http.stop();
        subscriptions.clear(); timer.shutdownNow();
    }
}
