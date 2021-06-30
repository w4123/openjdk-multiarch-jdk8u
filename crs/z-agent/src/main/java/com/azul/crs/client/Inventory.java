/*
 * Copyright 2019-2020 Azul Systems,
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.azul.crs.client;

import com.azul.crs.util.logging.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.function.Function;
import com.azul.crs.util.logging.LogChannel;

import static com.azul.crs.shared.Utils.currentTimeCount;

/** The model of VM inventory collected and reported by agent to CRS */
@LogChannel("inventory")
public class Inventory {
    public final static String INSTANCE_TAGS_PROPERTY = "com.azul.crs.instance.tags";
    public final static String HOST_NAME_KEY = "hostName";
    public final static String NETWORKS_KEY = "networks";
    public final static String SYSTEM_PROPS_KEY = "systemProperties";
    public final static String JVM_ARGS_KEY = "jvmArgs";
    public final static String MAIN_METHOD = "mainMethod";
    public final static String ENVIRONMENT_KEY = "osEnvironment";

    private Logger logger = Logger.getLogger(Inventory.class);
    private Map<String, Object> map = new LinkedHashMap<>();

    public Inventory() {
    }

    public Inventory populate() {
        map.put(HOST_NAME_KEY, hostName());
        map.put(SYSTEM_PROPS_KEY, systemProperties());
        map.put(JVM_ARGS_KEY, jvmArgs());
        map.put(ENVIRONMENT_KEY, osEnvironment());
        return this;
    }

    public Inventory networkInformation() {
        map.put(NETWORKS_KEY, networks());
        return this;
    }

    public Inventory mainMethod(String mainMethod) {
        map.put(MAIN_METHOD, mainMethod);
        return this;
    }

    public String hostName() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException uhe) {
            logger.warning("cannot get host name %s", uhe.toString());
        }
        String name = getHostNameViaReflection();
        if (name == null) {
            name = getHostNameFromNetworkInterface();
        }
        if (name == null) {
            name = "<UNKNOWN>";
        }
        return name;
    }

    public static String instanceTags() {
        return System.getProperties().getProperty(INSTANCE_TAGS_PROPERTY);
    }

    public Map systemProperties() {
        return System.getProperties();
    }

    public Map<String, String> osEnvironment() { return System.getenv(); }

    public static List<String> jvmArgs() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        return runtimeMXBean.getInputArguments();
    }

    private List<Network> networks() {
        try {
            List<Network> result = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if(ni.isUp() && !ni.isLoopback() && !ni.getName().startsWith("docker")) {
                        List<Address> addrList = new ArrayList<>();
                        for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                            // do not delay shutdown during startup
                            if (Client.isVMShutdownInitiated() && currentTimeCount() >= Client.getVMShutdownDeadline())
                                return Collections.emptyList();
                            addrList.add(new Address(addr.getCanonicalHostName(), getTrueIpAddress(addr)));
                        }
                        addrList.sort(Comparator.comparing(new Function<Address, String>() {
                            @Override
                            public String apply(Address a) {
                                return a.hostname;
                            }
                        }));
                        result.add(new Network(ni.getName(), addrList));
                    }
                } catch (SocketException e) {
                    logger.warning("cannot get network info %s", e.toString());
                }
            }
            result.sort(Comparator.comparing(new Function<Network, String>() {
                @Override
                public String apply(Network ni) {
                    return ni.interfaceName;
                }
            }));
            return  result;
        } catch (SocketException e) {
            logger.warning("cannot get network info %s", e.toString());
        }
        return Collections.emptyList();
    }

    private String getTrueIpAddress(InetAddress addr) {
        // Inet6Address.getHostAddress() appends '%' and interface name to the address
        String text = addr.getHostAddress();
        int pos = text.indexOf('%');
        return (pos < 0) ? text : text.substring(0, pos);
    }

    Map<String, Object> toMap() {
        return map;
    }

    /**
     * The first thing InetAddress.getLocalHost() does, it gets host name. And that's all we need here.
     * Other things InetAddress.getLocalHost().getHostName() does are not just needless in our case,
     * but can lead to UnknownHostException, see CRS-183.
     * For getting host name as such InetAddress uses a InetAddressImpl delegate (which is package-local)
     * There ia no way to get this info other than reflection.
     */
    private String getHostNameViaReflection() {
        try {
            Class clazz = Class.forName("java.net.Inet4AddressImpl");
            Method method = clazz.getDeclaredMethod("getLocalHostName");
            method.setAccessible(true);
            // don't restore setAccessible back - it only affects the reflection object
            // (clazz), not the Inet4AddressImpl class itself
            for (Constructor ctor : clazz.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 0) {
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance();
                    Object result = method.invoke(instance);
                    if (result instanceof String) {
                        return (String) result;
                    } else {
                        logger.warning("cannot get host name. internal error %s",
                            result == null ? null : result.getClass());
                        return null;
                    }
                }
            }
        } catch (ReflectiveOperationException | SecurityException e) {
            logger.warning("cannot get host name %s", e.toString());
        }
        return null;
    }

    private String getHostNameFromNetworkInterface()  {
        try {
            // we prefer ipv4 over ipv6 since canonical name returned by ipv4 is "better":
            // ipv4: aspb-dhcp-10-16-12-84.xxsystems.com
            // ipv6: fe80:0:0:0:f97b:80f:df4d:fb6c%ece98e743bxe372
            String candidateName = null;
            for(Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces(); nis.hasMoreElements();) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isUp() && !ni.isLoopback()) {
                    for (Enumeration<InetAddress> isa = ni.getInetAddresses(); isa.hasMoreElements();) {
                        InetAddress ia = isa.nextElement();
                        if (ia instanceof Inet4Address) {
                            return ia.getCanonicalHostName();
                        } else {
                            candidateName = ia.getCanonicalHostName();
                        }
                    }
                }
            }
            return candidateName;
        } catch (SocketException e) {
            logger.warning("cannot get host name for iface %s", e.toString());
        }
        return null;
    }

    private static class Network {
//        TODO @JsonProperty("interface")
        public final String interfaceName;
        public final List<Address> addresses;

        public Network(String interfaceName, List<Address> addresses) {
            this.interfaceName = interfaceName;
            this.addresses = addresses;
        }
    }

    private static class Address {
        public final String hostname;
        public final String address;

        public Address(String hostname, String address) {
            this.hostname = hostname;
            this.address = address;
        }
    }
}
