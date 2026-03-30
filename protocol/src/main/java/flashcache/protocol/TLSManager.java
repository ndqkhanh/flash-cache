package flashcache.protocol;

import javax.net.ssl.*;
import java.io.*;
import java.nio.channels.SocketChannel;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * TLS termination wrapper around Java's SSLContext.
 * Loads a keystore, creates an SSLContext for TLSv1.3, and wraps SocketChannels with SSLEngine.
 */
public class TLSManager {

    private final String keystorePath;
    private final char[] keystorePassword;

    public TLSManager(String keystorePath, String keystorePassword) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword.toCharArray();
    }

    // -----------------------------------------------------------------------
    // SSLContext creation
    // -----------------------------------------------------------------------

    /**
     * Load the keystore and create an SSLContext configured for TLSv1.3.
     */
    public SSLContext createSSLContext() throws Exception {
        KeyStore ks = loadKeyStore();

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    private KeyStore loadKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        File f = new File(keystorePath);
        if (!f.exists()) {
            throw new FileNotFoundException("Keystore not found: " + keystorePath);
        }
        try (InputStream is = new FileInputStream(f)) {
            ks.load(is, keystorePassword);
        }
        return ks;
    }

    // -----------------------------------------------------------------------
    // Wrap channel
    // -----------------------------------------------------------------------

    /**
     * Wrap a SocketChannel with an SSLEngine configured for server mode.
     */
    public SSLEngine wrapChannel(SocketChannel channel) throws Exception {
        SSLContext ctx = createSSLContext();
        SSLEngine engine = ctx.createSSLEngine();
        engine.setUseClientMode(false); // server mode
        return engine;
    }

    // -----------------------------------------------------------------------
    // Protocol / cipher suite queries
    // -----------------------------------------------------------------------

    /**
     * Return the list of supported SSL/TLS protocols.
     */
    public List<String> getSupportedProtocols() throws Exception {
        SSLContext ctx = createSSLContext();
        SSLEngine engine = ctx.createSSLEngine();
        return Arrays.asList(engine.getSupportedProtocols());
    }

    /**
     * Return the list of supported cipher suites.
     */
    public List<String> getSupportedCipherSuites() throws Exception {
        SSLContext ctx = createSSLContext();
        SSLEngine engine = ctx.createSSLEngine();
        return Arrays.asList(engine.getSupportedCipherSuites());
    }

    // -----------------------------------------------------------------------
    // Self-signed keystore generation (for testing)
    // -----------------------------------------------------------------------

    /**
     * Generate a self-signed keystore at the given path using keytool via ProcessBuilder.
     * Useful for tests; overwrites any existing file.
     */
    public static void generateSelfSignedKeystore(String path, String password) throws Exception {
        File f = new File(path);
        if (f.exists()) {
            f.delete();
        }
        // Ensure parent directory exists
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        ProcessBuilder pb = new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "flashcache",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=localhost, OU=Test, O=FlashCache, L=City, ST=State, C=US",
                "-keystore", path,
                "-storepass", password,
                "-keypass", password,
                "-storetype", "JKS",
                "-noprompt"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Drain output to avoid blocking
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("keytool failed (exit " + exitCode + "): " + new String(output));
        }
    }
}
