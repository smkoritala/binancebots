package bots.binance.client;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class BinanceCertificateFetcher {
   // private static final Logger logger = Logger.getLogger(BinanceCertificateFetcher.class.getName());
    public static Set<String> getAllSSLFingerprints(String hostname) throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(hostname, 443)) {
            socket.startHandshake();
            SSLSession session = socket.getSession();
            Certificate[] certs = session.getPeerCertificates();
            Set<String> fingerprints = new HashSet<>(Arrays.asList(
                    "sha256/7yd3jTwfNUbXxJ/hiqfCVZoRFx33ILm0Iw3CdYuyYD4=", // Example hardcoded cert
                    "sha256/Wec45nQiFwKvHtuHxSAMGkt19k+uPSw9JlEkxhvYPHk=", // Additional hardcoded cert
                    "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY="
                ));
            for (Certificate cert : certs) {
                X509Certificate x509Cert = (X509Certificate) cert;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] certHash = md.digest(x509Cert.getEncoded());
                String fingerprint = "sha256/" + Base64.getEncoder().encodeToString(certHash);
                fingerprints.add(fingerprint);
                //logger.info("✅ Certificate Fingerprint: " + fingerprint);
            }
            return fingerprints;
        }
    }
}
