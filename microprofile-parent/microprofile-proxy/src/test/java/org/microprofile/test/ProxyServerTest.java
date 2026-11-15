package org.microprofile.test;

import java.io.IOException;

import org.microprofile.proxy.ProxyServer;

public class ProxyServerTest {

    public static void main(String[] args) {
        try {
            ProxyServer proxyServer = new ProxyServer(1000, 20, 100 * 1024);
            proxyServer.listen();
            proxyServer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
