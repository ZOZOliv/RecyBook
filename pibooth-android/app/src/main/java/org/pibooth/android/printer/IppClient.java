package org.pibooth.android.printer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client IPP minimal (RFC 8010/8011) : envoie un JPEG par Print-Job,
 * sans boîte de dialogue. Compatible avec CUPS, les imprimantes AirPrint /
 * IPP Everywhere et la plupart des imprimantes photo à sublimation
 * partagées par un serveur CUPS.
 */
public final class IppClient {

    private static final int OP_PRINT_JOB = 0x0002;
    private static final int TAG_OPERATION = 0x01;
    private static final int TAG_JOB = 0x02;
    private static final int TAG_END = 0x03;
    private static final int TAG_INTEGER = 0x21;
    private static final int TAG_NAME = 0x42;
    private static final int TAG_KEYWORD = 0x44;
    private static final int TAG_URI = 0x45;
    private static final int TAG_CHARSET = 0x47;
    private static final int TAG_LANGUAGE = 0x48;
    private static final int TAG_MIME = 0x49;

    private IppClient() {
    }

    /**
     * @param printerUri ipp://hote:631/ipp/print, ipp://serveur:631/printers/Nom, ou http(s)://…
     * @param media      mot-clé média IPP (ex. « na_index-4x6_4x6in »), vide pour celui par défaut
     */
    public static void printJpeg(String printerUri, byte[] jpeg, int copies, String media) throws IOException {
        String ippUri = printerUri;
        String httpUrl;
        if (printerUri.startsWith("ipp://")) {
            httpUrl = "http://" + printerUri.substring(6);
        } else if (printerUri.startsWith("ipps://")) {
            httpUrl = "https://" + printerUri.substring(7);
        } else {
            httpUrl = printerUri;
            ippUri = printerUri.replaceFirst("^http", "ipp");
        }
        URL url = new URL(httpUrl);
        if (url.getPort() == -1 && printerUri.startsWith("ipp")) {
            url = new URL(url.getProtocol(), url.getHost(), 631, url.getFile());
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(header);
        d.writeShort(0x0101); // IPP/1.1
        d.writeShort(OP_PRINT_JOB);
        d.writeInt(1); // request-id
        d.writeByte(TAG_OPERATION);
        attr(d, TAG_CHARSET, "attributes-charset", "utf-8");
        attr(d, TAG_LANGUAGE, "attributes-natural-language", "fr");
        attr(d, TAG_URI, "printer-uri", ippUri);
        attr(d, TAG_NAME, "requesting-user-name", "pibooth");
        attr(d, TAG_NAME, "job-name", "pibooth");
        attr(d, TAG_MIME, "document-format", "image/jpeg");
        d.writeByte(TAG_JOB);
        d.writeByte(TAG_INTEGER);
        d.writeShort(6);
        d.write("copies".getBytes(StandardCharsets.US_ASCII));
        d.writeShort(4);
        d.writeInt(Math.max(1, copies));
        if (media != null && !media.isEmpty()) attr(d, TAG_KEYWORD, "media", media);
        attr(d, TAG_KEYWORD, "print-scaling", "fill");
        d.writeByte(TAG_END);
        d.flush();

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/ipp");
        conn.setFixedLengthStreamingMode(header.size() + jpeg.length);
        try (OutputStream out = conn.getOutputStream()) {
            header.writeTo(out);
            out.write(jpeg);
        }
        int http = conn.getResponseCode();
        if (http != 200) throw new IOException("Imprimante : HTTP " + http);
        try (InputStream in = conn.getInputStream()) {
            byte[] resp = new byte[8];
            int n = in.read(resp);
            if (n < 4) throw new IOException("Réponse IPP invalide");
            int status = ((resp[2] & 0xFF) << 8) | (resp[3] & 0xFF);
            if (status > 0x00FF) {
                throw new IOException(String.format("Imprimante : statut IPP 0x%04X", status));
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void attr(DataOutputStream d, int tag, String name, String value) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        d.writeByte(tag);
        d.writeShort(n.length);
        d.write(n);
        d.writeShort(v.length);
        d.write(v);
    }
}
