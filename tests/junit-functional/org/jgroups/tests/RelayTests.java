 package org.jgroups.tests;

 import org.jgroups.*;
 import org.jgroups.logging.Log;
 import org.jgroups.logging.LogFactory;
 import org.jgroups.protocols.LOCAL_PING;
 import org.jgroups.protocols.MERGE3;
 import org.jgroups.protocols.TCP;
 import org.jgroups.protocols.UNICAST3;
 import org.jgroups.protocols.pbcast.GMS;
 import org.jgroups.protocols.pbcast.NAKACK2;
 import org.jgroups.protocols.relay.*;
 import org.jgroups.protocols.relay.config.RelayConfig;
 import org.jgroups.protocols.relay.config.RelayConfig.SiteConfig;
 import org.jgroups.stack.Protocol;
 import org.jgroups.util.*;

 import java.io.DataInput;
 import java.io.DataOutput;
 import java.io.IOException;
 import java.net.InetAddress;
 import java.util.ArrayList;
 import java.util.Arrays;
 import java.util.List;
 import java.util.concurrent.TimeoutException;
 import java.util.function.Predicate;
 import java.util.stream.Collectors;
 import java.util.stream.Stream;

/**
 * Common functionality for relay tests
 * @author Bela Ban
 * @since  5.2.17
 */
public class RelayTests {
    protected final Log                log=LogFactory.getLog(getClass());
    protected static final InetAddress LOOPBACK;

    static {
        LOOPBACK=InetAddress.getLoopbackAddress();
    }


    protected static Protocol[] defaultStack(Protocol ... additional_protocols) {
        Protocol[] protocols={
          new TCP().setBindAddress(LOOPBACK),
          new LOCAL_PING(),
          new MERGE3().setMaxInterval(3000).setMinInterval(1000),
          new NAKACK2().useMcastXmit(false),
          new UNICAST3(),
          new GMS().printLocalAddress(false)
        };
        if(additional_protocols == null)
            return protocols;
        Protocol[] tmp=Arrays.copyOf(protocols, protocols.length + additional_protocols.length);
        System.arraycopy(additional_protocols, 0, tmp, protocols.length, additional_protocols.length);
        return tmp;
    }

    /**
     * Creates a node in a local site that connects to all given sites
     * @param site The local site
     * @param name The name of the node
     * @param bridge The name of the bridge cluster
     * @param sites The sites to which this site connects
     */
    protected static JChannel createNode(Class<? extends RELAY> cl, String site, String name, String bridge,
                                         String ... sites) throws Exception {
        return createNode(cl, site, name, bridge, true, sites);
    }

    protected static JChannel createNode(Class<? extends RELAY> cl, String site, String name, String bridge,
                                         boolean connect, String ... sites) throws Exception {
        RELAY relay=createSymmetricRELAY(cl, site, bridge, sites);
        JChannel ch=new JChannel(defaultStack(relay)).name(name);
        if(connect)
            ch.connect(site);
        return ch;
    }

    protected static JChannel createNode(Class<? extends RELAY> cl, String site, String node_name,
                                         int num_site_masters, boolean connect, MySiteConfig ... site_cfgs) throws Exception {
        RELAY relay=createAsymmetricRELAY(cl, site, site_cfgs).setMaxSiteMasters(num_site_masters);
        JChannel ch=new JChannel(defaultStack(relay)).name(node_name);
        if(connect)
            ch.connect(site);
        return ch;
    }


    protected static RELAY createSymmetricRELAY(Class<? extends RELAY> cl, String local_site, String bridge, String ... sites)
      throws Exception {
        RELAY relay=cl.getDeclaredConstructor().newInstance().site(local_site)
          .asyncRelayCreation(false)
          .delaySitesDown(false); // for compatibility with testSitesUp()
        for(String site: sites) {
            SiteConfig cfg=new SiteConfig(site)
              .addBridge(new RelayConfig.ProgrammaticBridgeConfig(bridge, defaultStack()));
            relay.addSite(site, cfg);
        }
        return relay;
    }

    protected static RELAY createAsymmetricRELAY(Class<? extends RELAY> cl, String local_site, MySiteConfig... site_cfgs)
      throws Exception {
        RELAY relay=cl.getDeclaredConstructor().newInstance().site(local_site).asyncRelayCreation(false);
        for(MySiteConfig cfg: site_cfgs) {
            SiteConfig site_cfg=new SiteConfig(cfg.site);
            for(String bridge_name: cfg.bridges)
                site_cfg.addBridge(new RelayConfig.ProgrammaticBridgeConfig(bridge_name, defaultStack()));
            for(Tuple<String,String> t: cfg.forwards)
                site_cfg.addForward(new RelayConfig.ForwardConfig(t.getVal1(), t.getVal2()));
            relay.addSite(cfg.site, site_cfg);
        }
        return relay;
    }

    protected static void waitUntilRoute(String site_name, boolean present,
                                         long timeout, long interval, JChannel ch) throws Exception {
        RELAY relay=ch.getProtocolStack().findProtocol(RELAY.class);
        if(relay == null)
            throw new IllegalArgumentException("protocol RELAY not found");

        Util.waitUntil(timeout, interval, () -> {
            Route route=relay.getRoute(site_name);
             return ((route != null && present) || (route == null && !present));
        });
    }

    protected static Route getRoute(JChannel ch, String site_name) {
        RELAY relay=ch.getProtocolStack().findProtocol(RELAY.class);
        return relay.getRoute(site_name);
    }

    protected static boolean isSiteMaster(JChannel ch) {
        RELAY r=ch.getProtocolStack().findProtocol(RELAY.class);
        return r != null && r.isSiteMaster();
    }

    /** Creates a singleton view for each channel listed and injects it */
    protected static void injectSingletonPartitions(JChannel ... channels) {
        for(JChannel ch: channels) {
            View view=View.create(ch.getAddress(), ch.getView().getViewId().getId()+1, ch.getAddress());
            GMS gms=ch.getProtocolStack().findProtocol(GMS.class);
            gms.installView(view);
        }
    }

    protected static void waitForBridgeView(int expected_size, long timeout, long interval, String cluster,
                                            JChannel... channels) {
        Util.waitUntilTrue(timeout, interval, () -> Stream.of(channels)
          .map(ch -> (RELAY)ch.getProtocolStack().findProtocol(RELAY.class))
          .map(r -> r.getBridgeView(cluster)).allMatch(v -> v != null && v.size() == expected_size));

        System.out.println("Bridge views:\n");
        for(JChannel ch: channels) {
            RELAY relay=ch.getProtocolStack().findProtocol(RELAY.class);
            View bridge_view=relay.getBridgeView(cluster);
            System.out.println(ch.getAddress() + ": " + bridge_view);
        }

        for(JChannel ch: channels) {
            RELAY relay=ch.getProtocolStack().findProtocol(RELAY.class);
            View bridge_view=relay.getBridgeView(cluster);
            assert bridge_view != null && bridge_view.size() == expected_size
              : ch.getAddress() + ": bridge view=" + bridge_view + ", expected=" + expected_size;
        }
    }

    protected static MyReceiver<Message> getReceiver(JChannel ch) {
        return (MyReceiver<Message>)ch.getReceiver();
    }

    protected static int receivedMessages(JChannel ch) {
        return getReceiver(ch).list().size();
    }

    protected static void assertNumMessages(int expected, JChannel ... channels) throws TimeoutException {
        assertNumMessages(expected, Arrays.asList(channels));
    }

    protected static void assertNumMessages(int expected, List<JChannel> channels) throws TimeoutException {
        assertNumMessages(expected, channels, true);
    }

    protected static void assertNumMessages(int expected, List<JChannel> channels, boolean reset) throws TimeoutException {
        try {
            Util.waitUntil(5000,100,
                           () -> channels.stream().map(ch -> getReceiver(ch).list()).allMatch(l -> l.size() == expected),
                           () -> msgs(channels));
        }
        finally {
            if(reset)
                channels.forEach(ch -> getReceiver(ch).reset());
        }
    }

    protected static boolean expectedUnicasts(List<Message> msgs,int expected) {
        return expectedDests(msgs,m -> m.dest() != null,expected);
    }

    protected static boolean expectedMulticasts(List<Message> msgs,int expected) {
        return expectedDests(msgs,m -> m.dest() == null,expected);
    }

    protected static boolean expectedDests(List<Message> msgs, Predicate<Message> p, int expected) {
        return msgs.stream().filter(p).count() == expected;
    }

    protected static void printMessages(JChannel ... channels) {
        System.out.println(msgs(channels));
    }

    protected static String msgs(JChannel... channels) {
        return msgs(Arrays.asList(channels));
    }

    protected static String msgs(List<JChannel> channels) {
        return channels.stream()
          .map(ch -> String.format("%s: %s",ch.address(),getReceiver(ch).list(Message::getObject)))
          .collect(Collectors.joining("\n"));
    }

    protected static class MyRouteStatusListener implements RouteStatusListener {
        protected final Address      local_addr;
        protected final List<String> up=new ArrayList<>(), down=new ArrayList<>();
        protected boolean            verbose;

        protected MyRouteStatusListener(Address local_addr) {
            this.local_addr=local_addr;
        }

        protected List<String>          up()               {return up;}
        protected List<String>          down()             {return down;}
        protected MyRouteStatusListener verbose(boolean b) {this.verbose=b; return this;}
        protected boolean               verbose()          {return verbose;}

        @Override public synchronized void sitesUp(String... sites) {
            if(verbose)
                System.out.printf("%s: UP(%s)\n", local_addr, Arrays.toString(sites));
            up.addAll(Arrays.asList(sites));
        }

        @Override public synchronized void sitesDown(String... sites) {
            if(verbose)
                System.out.printf("%s: DOWN(%s)\n", local_addr, Arrays.toString(sites));
            down.addAll(Arrays.asList(sites));
        }

        protected synchronized MyRouteStatusListener clear() {up.clear(); down.clear(); return this;}

        @Override
        public String toString() {
            return String.format("down: %s, up: %s", down, up);
        }
    }

    protected static class ResponseSender<T> extends MyReceiver<T> {
        protected final JChannel ch;

        public ResponseSender(JChannel ch) {
            this.ch=ch;
        }

        @Override
        public void receive(Message msg) {
            super.receive(msg);
            if(msg.dest() == null || msg.dest() instanceof SiteMaster) { // send unicast response back to sender
                Message rsp=new ObjectMessage(msg.src(),"rsp-" + ch.getAddress());
                if(msg.isFlagSet(Message.Flag.NO_RELAY))
                    rsp.setFlag(Message.Flag.NO_RELAY);
                try {
                    ch.send(rsp);
                }
                catch(Exception e) {
                    System.out.printf("%s: failed sending response: %s", ch.getAddress(), e);
                }
            }
        }
    }

    protected static class UnicastResponseSender<T> extends MyReceiver<T> {
        protected final JChannel ch;

        public UnicastResponseSender(JChannel ch) {
            this.ch=ch;
        }

        public void receive(Message msg) {
            super.receive(msg);
            Object obj=msg.getObject();
            Data data=(Data)obj;
            if(data.type == Data.Type.REQ) {
                Message rsp=new ObjectMessage(msg.src(), new Data(Data.Type.RSP,String.valueOf(ch.getAddress())));
                if(msg.isFlagSet(Message.Flag.NO_RELAY))
                    rsp.setFlag(Message.Flag.NO_RELAY);
                try {
                    ch.send(rsp);
                }
                catch(Exception e) {
                    System.out.printf("%s: failed sending response: %s",ch.getAddress(),e);
                }
            }
        }
    }

    protected static class SiteMasterPickerImpl implements SiteMasterPicker {
        public SiteMasterPickerImpl() {
        }

        public Address pickSiteMaster(List<Address> site_masters, Address original_sender) {
            return site_masters.get(0);
        }

        public Route pickRoute(String site, List<Route> routes, Address original_sender) {
            return routes.get(0);
        }
    }

    protected static class MySiteConfig {
        protected final String                     site;
        protected final List<String>               bridges;
        protected final List<Tuple<String,String>> forwards=new ArrayList<>();

        protected MySiteConfig(String site, String ... bridges) {
            this.site=site;
            this.bridges=Arrays.asList(bridges);
        }

        protected MySiteConfig addForward(String to, String gateway) {
            forwards.add(new Tuple<>(to,gateway));
            return this;
        }
    }

    protected static class Data implements SizeStreamable {
        enum Type {REQ,RSP}
        protected Type   type;
        protected String payload;

        public Data() {}
        public Data(Type t, String s) {
            type=t;
            payload=s;
        }

        public Type   type()    {return type;}
        public String payload() {return payload;}

        public int serializedSize() {
            return Integer.BYTES + Bits.sizeUTF(payload) +1;
        }

        public void writeTo(DataOutput out) throws IOException {
            out.writeInt(type.ordinal());
            Bits.writeString(payload, out);
        }

        public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
            this.type=Type.values()[in.readInt()];
            this.payload=Bits.readString(in);
        }

        public String toString() {
            return String.format("%s: %s", type, payload);
        }
    }
}
