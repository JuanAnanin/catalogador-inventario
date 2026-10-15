package catalogador.ia;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Certificados de confianza para las conexiones HTTPS con los servicios de IA.
 * <p>
 * Java trae su propia lista de autoridades de certificación, distinta de la del
 * sistema operativo. En muchas redes de hospital un cortafuegos inspecciona el
 * tráfico cifrado y presenta un certificado de la propia organización, que
 * informática instala en Windows (o en el llavero de macOS) pero no en Java: sin
 * esto, toda conexión fallaría con «PKIX path building failed». Aquí se acepta
 * un certificado si lo acepta Java <b>o</b> el almacén del sistema operativo.
 */
final class Certificados {

    private static volatile SSLContext contexto;
    private static volatile boolean intentado;

    private Certificados() {
    }

    /** Contexto TLS con las autoridades de Java y las del sistema, o null si no hay nada que añadir. */
    static SSLContext contexto() {
        if (!intentado) {
            synchronized (Certificados.class) {
                if (!intentado) {
                    contexto = crear();
                    intentado = true;
                }
            }
        }
        return contexto;
    }

    private static SSLContext crear() {
        try {
            X509ExtendedTrustManager java = gestor(null);
            List<X509ExtendedTrustManager> sistema = new ArrayList<>();
            String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            List<String> almacenes = so.contains("win") ? Arrays.asList("Windows-ROOT")
                    : so.contains("mac") ? Arrays.asList("KeychainStore-ROOT", "KeychainStore")
                    : new ArrayList<>();
            for (String tipo : almacenes) {
                try {
                    KeyStore ks = KeyStore.getInstance(tipo);
                    ks.load(null, null);
                    X509ExtendedTrustManager tm = gestor(ks);
                    if (tm != null) {
                        sistema.add(tm);
                    }
                } catch (Exception e) {
                    // ese almacén no existe en esta versión de Java o de sistema
                }
            }
            if (java == null || sistema.isEmpty()) {
                return null;
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new Combinado(java, sistema)}, null);
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }

    private static X509ExtendedTrustManager gestor(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager t : f.getTrustManagers()) {
            if (t instanceof X509ExtendedTrustManager) {
                return (X509ExtendedTrustManager) t;
            }
        }
        return null;
    }

    /** Acepta el certificado si lo acepta el primero o cualquiera de los demás. */
    private static final class Combinado extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager principal;
        private final List<X509ExtendedTrustManager> otros;

        Combinado(X509ExtendedTrustManager principal, List<X509ExtendedTrustManager> otros) {
            this.principal = principal;
            this.otros = otros;
        }

        private interface Comprobacion {
            void con(X509ExtendedTrustManager tm) throws CertificateException;
        }

        private void probar(Comprobacion c) throws CertificateException {
            try {
                c.con(principal);
            } catch (CertificateException e) {
                for (X509ExtendedTrustManager o : otros) {
                    try {
                        c.con(o);
                        return;
                    } catch (CertificateException ignorado) {
                        // se prueba el siguiente
                    }
                }
                throw e;
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] cadena, String tipo, Socket s) throws CertificateException {
            probar(tm -> tm.checkServerTrusted(cadena, tipo, s));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] cadena, String tipo, SSLEngine e) throws CertificateException {
            probar(tm -> tm.checkServerTrusted(cadena, tipo, e));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] cadena, String tipo) throws CertificateException {
            probar(tm -> tm.checkServerTrusted(cadena, tipo));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] cadena, String tipo, Socket s) throws CertificateException {
            principal.checkClientTrusted(cadena, tipo, s);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] cadena, String tipo, SSLEngine e) throws CertificateException {
            principal.checkClientTrusted(cadena, tipo, e);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] cadena, String tipo) throws CertificateException {
            principal.checkClientTrusted(cadena, tipo);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> l = new ArrayList<>(Arrays.asList(principal.getAcceptedIssuers()));
            for (X509TrustManager o : otros) {
                l.addAll(Arrays.asList(o.getAcceptedIssuers()));
            }
            return l.toArray(new X509Certificate[0]);
        }
    }
}
