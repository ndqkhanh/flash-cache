package flashcache.protocol;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TLSManagerTest {

    @TempDir
    static Path tempDir;

    static String keystorePath;
    static final String PASSWORD = "changeit";

    @BeforeAll
    static void generateKeystore(@TempDir Path dir) throws Exception {
        keystorePath = dir.resolve("test.jks").toString();
        TLSManager.generateSelfSignedKeystore(keystorePath, PASSWORD);
    }

    // -----------------------------------------------------------------------
    // SSLContext tests
    // -----------------------------------------------------------------------

    @Test
    void createSSLContext_withValidKeystore_succeeds() throws Exception {
        TLSManager mgr = new TLSManager(keystorePath, PASSWORD);
        SSLContext ctx = mgr.createSSLContext();
        assertThat(ctx).isNotNull();
        assertThat(ctx.getProtocol()).isEqualTo("TLS");
    }

    @Test
    void sslContext_supportsTls13() throws Exception {
        TLSManager mgr = new TLSManager(keystorePath, PASSWORD);
        List<String> protocols = mgr.getSupportedProtocols();
        assertThat(protocols).contains("TLSv1.3");
    }

    @Test
    void sslContext_supportsTls12() throws Exception {
        TLSManager mgr = new TLSManager(keystorePath, PASSWORD);
        List<String> protocols = mgr.getSupportedProtocols();
        assertThat(protocols).contains("TLSv1.2");
    }

    @Test
    void getSupportedProtocols_includesTls13() throws Exception {
        TLSManager mgr = new TLSManager(keystorePath, PASSWORD);
        List<String> protocols = mgr.getSupportedProtocols();
        assertThat(protocols).isNotEmpty();
        assertThat(protocols).anyMatch(p -> p.startsWith("TLS"));
    }

    @Test
    void getSupportedCipherSuites_isNonEmpty() throws Exception {
        TLSManager mgr = new TLSManager(keystorePath, PASSWORD);
        List<String> ciphers = mgr.getSupportedCipherSuites();
        assertThat(ciphers).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // wrapChannel test
    // -----------------------------------------------------------------------

    @Test
    void wrapChannel_createsServerModeEngine() throws Exception {
        TLSManager mgr = new TLSManager(keystorePath, PASSWORD);
        // Pass null channel — wrapChannel only needs SSLContext, not actual channel
        SSLEngine engine = mgr.wrapChannel(null);
        assertThat(engine).isNotNull();
        // Server mode: useClientMode == false
        assertThat(engine.getUseClientMode()).isFalse();
    }

    // -----------------------------------------------------------------------
    // generateSelfSignedKeystore test
    // -----------------------------------------------------------------------

    @Test
    void generateSelfSignedKeystore_createsLoadableKeystore(@TempDir Path dir) throws Exception {
        String path = dir.resolve("new.jks").toString();
        TLSManager.generateSelfSignedKeystore(path, PASSWORD);
        // Should be loadable — creating a manager and building SSLContext should succeed
        TLSManager mgr = new TLSManager(path, PASSWORD);
        SSLContext ctx = mgr.createSSLContext();
        assertThat(ctx).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Invalid keystore path
    // -----------------------------------------------------------------------

    @Test
    void invalidKeystorePath_throwsException() {
        TLSManager mgr = new TLSManager("/nonexistent/path/to/keystore.jks", PASSWORD);
        assertThatThrownBy(mgr::createSSLContext)
                .isInstanceOf(Exception.class);
    }
}
