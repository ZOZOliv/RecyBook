package org.pibooth.android.ptp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Réponse d'une transaction PTP : code, paramètres et éventuelle phase de données. */
public final class PtpResponse {

    public final int code;
    public final int[] params;
    public final byte[] data;

    PtpResponse(int code, int[] params, byte[] data) {
        this.code = code;
        this.params = params;
        this.data = data;
    }

    public boolean isOk() {
        return code == Ptp.RC_OK;
    }

    public int param(int index) {
        return index < params.length ? params[index] : 0;
    }

    /** Lecteur little-endian sur la phase de données (vide si absente). */
    public ByteBuffer dataBuffer() {
        byte[] d = data != null ? data : new byte[0];
        return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Lève une exception si la réponse n'est pas OK. */
    public PtpResponse check(String what) throws PtpException {
        if (!isOk()) {
            throw new PtpException(what + " a échoué", code);
        }
        return this;
    }
}
