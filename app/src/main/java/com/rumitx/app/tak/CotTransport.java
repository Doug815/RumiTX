package com.rumitx.app.tak;
import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;
/** Bounded datagrams; multicast TTL one keeps sharing on the selected LAN. */
public final class CotTransport implements Closeable {
 private MulticastSocket socket;
 public synchronized void send(String xml,NetworkInterface network)throws IOException {byte[] bytes=xml.getBytes(StandardCharsets.UTF_8);if(bytes.length>60000)throw new IOException("CoT event exceeds UDP size limit");if(socket==null||socket.isClosed()){socket=new MulticastSocket();socket.setTimeToLive(1);}if(network!=null)socket.setNetworkInterface(network);socket.send(new DatagramPacket(bytes,bytes.length,InetAddress.getByName("239.2.3.1"),6969));}
 public synchronized void close(){if(socket!=null)socket.close();socket=null;}
}
