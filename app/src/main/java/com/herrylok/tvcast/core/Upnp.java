package com.herrylok.tvcast.core;

import java.io.StringReader;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** UPnP AV v1 device descriptions and SOAP control, independent of Android. */
public final class Upnp {
    public static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String AV = "AVTransport", RC = "RenderingControl", CM = "ConnectionManager";
    public static final String[] SERVICES = { AV, RC, CM };
    public static final String PROTOCOLS = "http-get:*:video/mp4:*,http-get:*:video/mpeg:*,"
            + "http-get:*:video/mp2t:*,http-get:*:application/vnd.apple.mpegurl:*,"
            + "http-get:*:application/x-mpegURL:*,http-get:*:application/dash+xml:*,"
            + "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*";
    private final Playback player;
    public Upnp(Playback player) { this.player = player; }
    public static String type(String service) { return "urn:schemas-upnp-org:service:" + service + ":1"; }
    public static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
    public static String tag(String name, String text) { return "<" + name + ">" + xml(text) + "</" + name + ">"; }
    public static String time(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    public static long parseTime(String text) {
        if (!text.matches("[0-9]{1,6}:[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,3})?")) throw new Fault(402, "Invalid Args");
        String[] parts = text.split(":");
        return Long.parseLong(parts[0]) * 3600000 + Long.parseLong(parts[1]) * 60000
                + (long) (Double.parseDouble(parts[2]) * 1000);
    }
    public static String description(String uuid, String name) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><device>");
        b.append(tag("deviceType", DEVICE)).append(tag("friendlyName", name))
                .append(tag("manufacturer", "EasyCast"))
                .append(tag("modelDescription", "Personal TV video receiver"))
                .append(tag("modelName", "EasyCast"))
                .append(tag("modelNumber", "1.0.0")).append(tag("UDN", uuid)).append("<serviceList>");
        for (String s : SERVICES) b.append("<service>").append(tag("serviceType", type(s)))
                .append(tag("serviceId", "urn:upnp-org:serviceId:" + s))
                .append(tag("SCPDURL", "/" + s + "/scpd.xml"))
                .append(tag("controlURL", "/" + s + "/control"))
                .append(tag("eventSubURL", "/" + s + "/event")).append("</service>");
        return b.append("</serviceList></device></root>").toString();
    }

    public String control(String service, String soapAction, String body) {
        Element action = actionElement(body);
        String name = action.getLocalName();
        if (!type(service).equals(action.getNamespaceURI())
                || !soapAction.replace("\"", "").equals(type(service) + "#" + name)) throw new Fault(401, "Invalid Action");
        Map<String, String> args = new LinkedHashMap<>();
        for (Node n = action.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) args.put(n.getLocalName(), n.getTextContent());
        }
        Definition def = definitions(service);
        String[][] signature = def.actions.get(name);
        if (signature == null) throw new Fault(401, "Invalid Action");
        for (String[] arg : signature) if (arg[1].equals("in") && !args.containsKey(arg[0])) throw new Fault(402, "Invalid Args");
        if (args.containsKey("InstanceID") && !args.get("InstanceID").equals("0")) throw new Fault(718, "Invalid InstanceID");
        if (args.containsKey("Channel") && !args.get("Channel").equals("Master")) throw new Fault(402, "Invalid Args");
        Playback.State s = player.state();
        String result = "";
        if (service.equals(AV)) {
            switch (name) {
                case "SetAVTransportURI":
                    String uri = args.get("CurrentURI");
                    try {
                        URI parsed = new URI(uri);
                        if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                                || parsed.getHost() == null || parsed.getUserInfo() != null) throw new Exception();
                    } catch (Exception e) { throw new Fault(714, "Illegal MIME-type or URI"); }
                    player.load(uri, args.get("CurrentURIMetaData")); break;
                case "Play":
                    if (!args.get("Speed").equals("1")) throw new Fault(717, "Play speed not supported");
                    if (s.uri.isEmpty()) throw new Fault(701, "Transition not available");
                    player.play(); break;
                case "Pause":
                    if (!s.transport.equals("PLAYING")) throw new Fault(701, "Transition not available");
                    player.pause(); break;
                case "Stop": player.stop(); break;
                case "Seek":
                    if (!args.get("Unit").equals("REL_TIME")) throw new Fault(710, "Seek mode not supported");
                    if (!s.seekable) throw new Fault(710, "Seek mode not supported");
                    long target = parseTime(args.get("Target"));
                    if (s.duration > 0 && target > s.duration) throw new Fault(711, "Illegal seek target");
                    player.seek(target); break;
                case "GetTransportInfo": result = tag("CurrentTransportState", s.transport) + tag("CurrentTransportStatus", s.status) + tag("CurrentSpeed", "1"); break;
                case "GetPositionInfo": result = tag("Track", s.uri.isEmpty() ? "0" : "1") + tag("TrackDuration", time(s.duration))
                        + tag("TrackMetaData", s.metadata) + tag("TrackURI", s.uri) + tag("RelTime", time(s.position))
                        + tag("AbsTime", time(s.position)) + tag("RelCount", "2147483647") + tag("AbsCount", "2147483647"); break;
                case "GetMediaInfo": result = tag("NrTracks", s.uri.isEmpty() ? "0" : "1") + tag("MediaDuration", time(s.duration))
                        + tag("CurrentURI", s.uri) + tag("CurrentURIMetaData", s.metadata) + tag("NextURI", "") + tag("NextURIMetaData", "")
                        + tag("PlayMedium", s.uri.isEmpty() ? "NONE" : "NETWORK") + tag("RecordMedium", "NOT_IMPLEMENTED") + tag("WriteStatus", "NOT_IMPLEMENTED"); break;
                case "GetDeviceCapabilities": result = tag("PlayMedia", "NETWORK") + tag("RecMedia", "NOT_IMPLEMENTED") + tag("RecQualityModes", "NOT_IMPLEMENTED"); break;
                case "GetTransportSettings": result = tag("PlayMode", "NORMAL") + tag("RecQualityMode", "NOT_IMPLEMENTED"); break;
                case "GetCurrentTransportActions": result = tag("Actions", actions(s)); break;
                default: throw new Fault(401, "Invalid Action");
            }
        } else if (service.equals(RC)) {
            switch (name) {
                case "GetVolume": result = tag("CurrentVolume", Integer.toString(s.volume)); break;
                case "SetVolume":
                    int volume;
                    try { volume = Integer.parseInt(args.get("DesiredVolume")); }
                    catch (NumberFormatException e) { throw new Fault(402, "Invalid Args"); }
                    if (volume < 0 || volume > 100) throw new Fault(402, "Invalid Args");
                    player.volume(volume); break;
                case "GetMute": result = tag("CurrentMute", s.muted ? "1" : "0"); break;
                case "SetMute":
                    String m = args.get("DesiredMute");
                    if (!(m.equals("0") || m.equals("1") || m.equals("true") || m.equals("false"))) throw new Fault(402, "Invalid Args");
                    player.mute(m.equals("1") || m.equals("true")); break;
                case "ListPresets": result = tag("CurrentPresetNameList", "FactoryDefaults"); break;
                case "SelectPreset":
                    if (!args.get("PresetName").equals("FactoryDefaults")) throw new Fault(701, "Invalid Name");
                    player.volume(100); player.mute(false); break;
                default: throw new Fault(401, "Invalid Action");
            }
        } else if (service.equals(CM)) {
            switch (name) {
                case "GetProtocolInfo": result = tag("Source", "") + tag("Sink", PROTOCOLS); break;
                case "GetCurrentConnectionIDs": result = tag("ConnectionIDs", "0"); break;
                case "GetCurrentConnectionInfo":
                    if (!args.get("ConnectionID").equals("0")) throw new Fault(706, "Invalid connection reference");
                    result = tag("RcsID", "0") + tag("AVTransportID", "0") + tag("ProtocolInfo", "")
                            + tag("PeerConnectionManager", "") + tag("PeerConnectionID", "-1") + tag("Direction", "Input") + tag("Status", "OK"); break;
                default: throw new Fault(401, "Invalid Action");
            }
        } else throw new Fault(401, "Invalid Action");
        return envelope("<u:" + name + "Response xmlns:u=\"" + type(service) + "\">" + result + "</u:" + name + "Response>");
    }

    private static Element actionElement(String body) {
        try {
            // Disallow DTDs before parsing, including on Android parsers without JAXP feature flags.
            if (body.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || body.toUpperCase(Locale.ROOT).contains("<!ENTITY")) throw new Exception();
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true); f.setExpandEntityReferences(false);
            Element root = f.newDocumentBuilder().parse(new InputSource(new StringReader(body))).getDocumentElement();
            if (!"Envelope".equals(root.getLocalName()) || !"http://schemas.xmlsoap.org/soap/envelope/".equals(root.getNamespaceURI())) throw new Exception();
            NodeList list = root.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body");
            if (list.getLength() != 1) throw new Exception();
            Element action = null;
            for (Node n = list.item(0).getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) {
                if (action != null) throw new Exception();
                action = (Element) n;
            }
            if (action == null) throw new Exception();
            return action;
        } catch (Exception e) { throw new Fault(402, "Invalid Args"); }
    }
    public static String envelope(String content) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" + content + "</s:Body></s:Envelope>";
    }
    public static final class Fault extends RuntimeException {
        public final int code;
        public Fault(int code, String message) { super(message); this.code = code; }
        public String body() { return envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" + tag("errorCode", Integer.toString(code)) + tag("errorDescription", getMessage()) + "</UPnPError></detail></s:Fault>"); }
    }
    private static String actions(Playback.State s) {
        if (s.uri.isEmpty()) return "";
        return (s.transport.equals("PLAYING") ? "Pause,Stop" : "Play,Stop") + (s.seekable ? ",Seek" : "");
    }
    public String event(String service) {
        Playback.State s = player.state();
        String data;
        if (service.equals(CM)) data = "<e:property>" + tag("SourceProtocolInfo", "") + "</e:property><e:property>" + tag("SinkProtocolInfo", PROTOCOLS) + "</e:property><e:property>" + tag("CurrentConnectionIDs", "0") + "</e:property>";
        else {
            String values = service.equals(AV)
                    ? value("TransportState", s.transport) + value("TransportStatus", s.status) + value("TransportPlaySpeed", "1")
                    + value("AVTransportURI", s.uri) + value("AVTransportURIMetaData", s.metadata)
                    + value("CurrentTrackURI", s.uri) + value("CurrentTrackMetaData", s.metadata)
                    + value("NumberOfTracks", s.uri.isEmpty() ? "0" : "1") + value("CurrentTrack", s.uri.isEmpty() ? "0" : "1")
                    + value("CurrentTrackDuration", time(s.duration)) + value("CurrentMediaDuration", time(s.duration))
                    + value("CurrentTransportActions", actions(s)) + value("CurrentPlayMode", "NORMAL")
                    : "<Volume channel=\"Master\" val=\"" + s.volume + "\"/><Mute channel=\"Master\" val=\"" + (s.muted ? "1" : "0") + "\"/>";
            String change = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/" + (service.equals(AV) ? "AVT" : "RCS") + "/\"><InstanceID val=\"0\">" + values + "</InstanceID></Event>";
            data = "<e:property>" + tag("LastChange", change) + "</e:property>";
        }
        return "<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" + data + "</e:propertyset>";
    }
    private static String value(String key, String value) { return "<" + key + " val=\"" + xml(value) + "\"/>"; }

    private static final class Definition {
        final Map<String, String[]> variables = new LinkedHashMap<>();
        final Map<String, String[][]> actions = new LinkedHashMap<>();
        void variable(String name, String type) { variables.put(name, new String[] {type, "no"}); }
        void action(String name, String... specs) {
            String[][] args = new String[specs.length][];
            for (int i = 0; i < specs.length; i++) args[i] = specs[i].split(":");
            actions.put(name, args);
        }
    }
    private static Definition definitions(String service) {
        Definition d = new Definition();
        if (service.equals(AV)) {
            for (String v : new String[]{"TransportState", "TransportStatus", "PlaybackStorageMedium", "RecordStorageMedium", "PossiblePlaybackStorageMedia", "PossibleRecordStorageMedia", "CurrentPlayMode", "TransportPlaySpeed", "RecordMediumWriteStatus", "CurrentRecordQualityMode", "PossibleRecordQualityModes", "CurrentTrackDuration", "CurrentMediaDuration", "CurrentTrackMetaData", "CurrentTrackURI", "AVTransportURI", "AVTransportURIMetaData", "NextAVTransportURI", "NextAVTransportURIMetaData", "RelativeTimePosition", "AbsoluteTimePosition", "CurrentTransportActions", "A_ARG_TYPE_SeekMode", "A_ARG_TYPE_SeekTarget"}) d.variable(v, "string");
            for (String v : new String[]{"NumberOfTracks", "CurrentTrack", "A_ARG_TYPE_InstanceID"}) d.variable(v, "ui4");
            d.variable("RelativeCounterPosition", "i4"); d.variable("AbsoluteCounterPosition", "i4");
            String id = "InstanceID:in:A_ARG_TYPE_InstanceID";
            d.action("SetAVTransportURI", id, "CurrentURI:in:AVTransportURI", "CurrentURIMetaData:in:AVTransportURIMetaData");
            d.action("Play", id, "Speed:in:TransportPlaySpeed"); d.action("Pause", id); d.action("Stop", id);
            d.action("Seek", id, "Unit:in:A_ARG_TYPE_SeekMode", "Target:in:A_ARG_TYPE_SeekTarget");
            d.action("GetTransportInfo", id, "CurrentTransportState:out:TransportState", "CurrentTransportStatus:out:TransportStatus", "CurrentSpeed:out:TransportPlaySpeed");
            d.action("GetPositionInfo", id, "Track:out:CurrentTrack", "TrackDuration:out:CurrentTrackDuration", "TrackMetaData:out:CurrentTrackMetaData", "TrackURI:out:CurrentTrackURI", "RelTime:out:RelativeTimePosition", "AbsTime:out:AbsoluteTimePosition", "RelCount:out:RelativeCounterPosition", "AbsCount:out:AbsoluteCounterPosition");
            d.action("GetMediaInfo", id, "NrTracks:out:NumberOfTracks", "MediaDuration:out:CurrentMediaDuration", "CurrentURI:out:AVTransportURI", "CurrentURIMetaData:out:AVTransportURIMetaData", "NextURI:out:NextAVTransportURI", "NextURIMetaData:out:NextAVTransportURIMetaData", "PlayMedium:out:PlaybackStorageMedium", "RecordMedium:out:RecordStorageMedium", "WriteStatus:out:RecordMediumWriteStatus");
            d.action("GetDeviceCapabilities", id, "PlayMedia:out:PossiblePlaybackStorageMedia", "RecMedia:out:PossibleRecordStorageMedia", "RecQualityModes:out:PossibleRecordQualityModes");
            d.action("GetTransportSettings", id, "PlayMode:out:CurrentPlayMode", "RecQualityMode:out:CurrentRecordQualityMode");
            d.action("GetCurrentTransportActions", id, "Actions:out:CurrentTransportActions");
        } else if (service.equals(RC)) {
            d.variable("A_ARG_TYPE_InstanceID", "ui4"); d.variable("A_ARG_TYPE_Channel", "string");
            d.variable("Volume", "ui2"); d.variable("Mute", "boolean");
            d.variable("PresetNameList", "string"); d.variable("A_ARG_TYPE_PresetName", "string");
            String id = "InstanceID:in:A_ARG_TYPE_InstanceID", channel = "Channel:in:A_ARG_TYPE_Channel";
            d.action("GetVolume", id, channel, "CurrentVolume:out:Volume");
            d.action("SetVolume", id, channel, "DesiredVolume:in:Volume");
            d.action("GetMute", id, channel, "CurrentMute:out:Mute");
            d.action("SetMute", id, channel, "DesiredMute:in:Mute");
            d.action("ListPresets", id, "CurrentPresetNameList:out:PresetNameList");
            d.action("SelectPreset", id, "PresetName:in:A_ARG_TYPE_PresetName");
        } else if (service.equals(CM)) {
            for (String v : new String[]{"SourceProtocolInfo", "SinkProtocolInfo", "CurrentConnectionIDs", "A_ARG_TYPE_ProtocolInfo", "A_ARG_TYPE_ConnectionManager", "A_ARG_TYPE_Direction", "A_ARG_TYPE_ConnectionStatus"}) d.variable(v, "string");
            for (String v : new String[]{"A_ARG_TYPE_ConnectionID", "A_ARG_TYPE_AVTransportID", "A_ARG_TYPE_RcsID"}) d.variable(v, "i4");
            d.action("GetProtocolInfo", "Source:out:SourceProtocolInfo", "Sink:out:SinkProtocolInfo");
            d.action("GetCurrentConnectionIDs", "ConnectionIDs:out:CurrentConnectionIDs");
            d.action("GetCurrentConnectionInfo", "ConnectionID:in:A_ARG_TYPE_ConnectionID", "RcsID:out:A_ARG_TYPE_RcsID", "AVTransportID:out:A_ARG_TYPE_AVTransportID", "ProtocolInfo:out:A_ARG_TYPE_ProtocolInfo", "PeerConnectionManager:out:A_ARG_TYPE_ConnectionManager", "PeerConnectionID:out:A_ARG_TYPE_ConnectionID", "Direction:out:A_ARG_TYPE_Direction", "Status:out:A_ARG_TYPE_ConnectionStatus");
            for (String v : new String[]{"SourceProtocolInfo", "SinkProtocolInfo", "CurrentConnectionIDs"}) d.variables.get(v)[1] = "yes";
        } else throw new Fault(401, "Invalid Action");
        if (!service.equals(CM)) d.variables.put("LastChange", new String[]{"string", "yes"});
        return d;
    }
    public static String scpd(String service) {
        Definition d = definitions(service);
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
        for (Map.Entry<String, String[][]> a : d.actions.entrySet()) {
            b.append("<action>").append(tag("name", a.getKey())).append("<argumentList>");
            for (String[] arg : a.getValue()) b.append("<argument>").append(tag("name", arg[0])).append(tag("direction", arg[1])).append(tag("relatedStateVariable", arg[2])).append("</argument>");
            b.append("</argumentList></action>");
        }
        b.append("</actionList><serviceStateTable>");
        for (Map.Entry<String, String[]> v : d.variables.entrySet()) {
            b.append("<stateVariable sendEvents=\"").append(v.getValue()[1]).append("\">").append(tag("name", v.getKey())).append(tag("dataType", v.getValue()[0]));
            if (v.getKey().equals("Volume")) b.append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>");
            b.append("</stateVariable>");
        }
        return b.append("</serviceStateTable></scpd>").toString();
    }
}
