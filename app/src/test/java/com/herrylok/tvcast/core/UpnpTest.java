package com.herrylok.tvcast.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.StringReader;
import java.util.HashSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

public class UpnpTest {
    static final class Fake implements Playback {
        volatile String uri = "", metadata = "", transport = "NO_MEDIA_PRESENT";
        volatile long position;
        volatile int volume = 100;
        volatile boolean muted, seekable = true;
        public State state() { return new State(uri, metadata, transport, "OK", position, 120000, volume, muted, seekable); }
        public void load(String u, String m) { uri = u; metadata = m; transport = "STOPPED"; }
        public void play() { transport = "PLAYING"; }
        public void pause() { transport = "PAUSED_PLAYBACK"; }
        public void stop() { transport = "STOPPED"; }
        public void seek(long p) { position = p; }
        public void volume(int v) { volume = v; }
        public void mute(boolean m) { muted = m; }
    }
    static String soap(String service, String action, String args) {
        return Upnp.envelope("<u:" + action + " xmlns:u=\"" + Upnp.type(service) + "\">" + args + "</u:" + action + ">");
    }
    static String call(Upnp p, String service, String action, String args) {
        return p.control(service, "\"" + Upnp.type(service) + "#" + action + "\"", soap(service, action, args));
    }
    static String id() { return "<InstanceID>0</InstanceID>"; }
    @Test public void completeVodControlAndEscapedMetadata() {
        Fake fake = new Fake(); Upnp p = new Upnp(fake);
        String uri = "https://example.test/movie.mp4?a=1&b=2";
        String metadata = "<DIDL-Lite><title>篮球 & 比赛</title></DIDL-Lite>";
        call(p, Upnp.AV, "SetAVTransportURI", id() + Upnp.tag("CurrentURI", uri) + Upnp.tag("CurrentURIMetaData", metadata));
        assertEquals(uri, fake.uri); assertEquals(metadata, fake.metadata);
        call(p, Upnp.AV, "Play", id() + "<Speed>1</Speed>"); assertEquals("PLAYING", fake.transport);
        call(p, Upnp.AV, "Seek", id() + "<Unit>REL_TIME</Unit><Target>00:01:03.250</Target>"); assertEquals(63250, fake.position);
        call(p, Upnp.AV, "Pause", id()); assertEquals("PAUSED_PLAYBACK", fake.transport);
        assertTrue(call(p, Upnp.AV, "GetPositionInfo", id()).contains("<RelTime>00:01:03</RelTime>"));
        assertTrue(call(p, Upnp.AV, "GetPositionInfo", id()).contains("a=1&amp;b=2"));
        call(p, Upnp.AV, "Stop", id()); assertEquals("STOPPED", fake.transport);
    }
    @Test public void rejectsUnsupportedUriAndMissingArgs() {
        Fake f = new Fake(); Upnp p = new Upnp(f);
        for (String uri : new String[]{"file:///etc/passwd", "content://media/1", "javascript:alert(1)", "http:///bad", "http://user:pass@example.test/video"}) {
            Upnp.Fault error = assertThrows(Upnp.Fault.class, () -> call(p, Upnp.AV, "SetAVTransportURI", id() + Upnp.tag("CurrentURI", uri) + "<CurrentURIMetaData/>"));
            assertEquals(714, error.code); assertEquals("", f.uri);
        }
        assertEquals(402, assertThrows(Upnp.Fault.class, () -> call(p, Upnp.AV, "SetAVTransportURI", id())).code);
    }
    @Test public void validatesActionInstanceAndSpeed() {
        Upnp p = new Upnp(new Fake());
        assertEquals(718, assertThrows(Upnp.Fault.class, () -> call(p, Upnp.AV, "Play", "<InstanceID>1</InstanceID><Speed>1</Speed>")).code);
        assertEquals(717, assertThrows(Upnp.Fault.class, () -> call(p, Upnp.AV, "Play", id() + "<Speed>2</Speed>")).code);
        assertEquals(701, assertThrows(Upnp.Fault.class, () -> call(p, Upnp.AV, "Play", id() + "<Speed>1</Speed>")).code);
        assertEquals(401, assertThrows(Upnp.Fault.class, () -> p.control(Upnp.AV, Upnp.type(Upnp.AV) + "#Stop", soap(Upnp.AV, "Play", id()))).code);
    }
    @Test public void liveCannotSeekAndVodHasBounds() {
        Fake f = new Fake(); Upnp p = new Upnp(f); f.seekable = false;
        assertEquals(710, assertThrows(Upnp.Fault.class, () -> call(p, Upnp.AV, "Seek", id() + "<Unit>REL_TIME</Unit><Target>00:00:10</Target>")).code);
        f.seekable = true;
        assertEquals(711, assertThrows(Upnp.Fault.class, () -> call(p, Upnp.AV, "Seek", id() + "<Unit>REL_TIME</Unit><Target>01:00:00</Target>")).code);
        assertThrows(Upnp.Fault.class, () -> Upnp.parseTime("00:65:00"));
        assertThrows(Upnp.Fault.class, () -> Upnp.parseTime("-1:00:00"));
    }
    @Test public void validatesVolumeAndMute() {
        Fake f = new Fake(); Upnp p = new Upnp(f); String args = id() + "<Channel>Master</Channel>";
        call(p, Upnp.RC, "SetVolume", args + "<DesiredVolume>32</DesiredVolume>"); assertEquals(32, f.volume);
        assertThrows(Upnp.Fault.class, () -> call(p, Upnp.RC, "SetVolume", args + "<DesiredVolume>101</DesiredVolume>"));
        call(p, Upnp.RC, "SetMute", args + "<DesiredMute>true</DesiredMute>"); assertTrue(f.muted);
        assertThrows(Upnp.Fault.class, () -> call(p, Upnp.RC, "SetMute", args + "<DesiredMute>yes</DesiredMute>"));
    }
    @Test public void rejectsDtdAndMalformedSoap() {
        Upnp p = new Upnp(new Fake());
        String xml = "<!DOCTYPE x [<!ENTITY ex SYSTEM 'file:///etc/passwd'>]>" + soap(Upnp.AV, "Stop", id());
        assertEquals(402, assertThrows(Upnp.Fault.class, () -> p.control(Upnp.AV, "", xml)).code);
        assertThrows(Upnp.Fault.class, () -> p.control(Upnp.AV, "", "<Body/>"));
    }
    private Document parse(String text) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
    }
    @Test public void serviceDescriptionsResolveEveryArgument() throws Exception {
        for (String s : Upnp.SERVICES) {
            Document doc = parse(Upnp.scpd(s)); HashSet<String> variables = new HashSet<>();
            NodeList states = doc.getElementsByTagName("stateVariable");
            for (int i = 0; i < states.getLength(); i++) variables.add(((Element) states.item(i)).getElementsByTagName("name").item(0).getTextContent());
            NodeList refs = doc.getElementsByTagName("relatedStateVariable");
            for (int i = 0; i < refs.getLength(); i++) assertTrue(variables.contains(refs.item(i).getTextContent()));
        }
        assertEquals(3, parse(Upnp.description("uuid:test", "客厅 & TV")).getElementsByTagName("service").getLength());
    }
    @Test public void eventContainsValidNestedLastChange() throws Exception {
        Fake f = new Fake(); f.load("http://example.test/a?x=1&y=2", "<DIDL-Lite/>"); f.play();
        Document event = parse(new Upnp(f).event(Upnp.AV));
        Document change = parse(event.getElementsByTagName("LastChange").item(0).getTextContent());
        assertEquals("PLAYING", ((Element)change.getElementsByTagName("TransportState").item(0)).getAttribute("val"));
        assertEquals(f.uri, ((Element)change.getElementsByTagName("AVTransportURI").item(0)).getAttribute("val"));
    }
    @Test public void ssdpRequiresRealDiscoveryRequest() {
        assertEquals("ssdp:all", RendererServer.searchHeaders("M-SEARCH * HTTP/1.1\r\nMan: \"ssdp:discover\"\r\nST: ssdp:all\r\nMX: 1\r\n\r\n").get("st"));
        assertTrue(RendererServer.searchHeaders("NOTIFY * HTTP/1.1\r\nST: ssdp:all\r\n").isEmpty());
        assertTrue(RendererServer.searchHeaders("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n").isEmpty());
    }
    @Test public void callbacksCannotTargetAnotherHostOrResolveDns() {
        assertNotNull(RendererServer.validateCallback("<http://192.168.1.4:3456/events>", "192.168.1.4"));
        for (String target : new String[]{"<http://127.0.0.1/events>", "<http://example.com/events>", "<http://192.168.1.5/events>", "<file:///tmp/x>", "<http://user@192.168.1.4/events>"}) assertNull(RendererServer.validateCallback(target, "192.168.1.4"));
    }
}
