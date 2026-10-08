package lb.proxy;

import java.io.IOException;

public interface Proxy {
    void start() throws IOException;
    void stop();
    int port();
}
