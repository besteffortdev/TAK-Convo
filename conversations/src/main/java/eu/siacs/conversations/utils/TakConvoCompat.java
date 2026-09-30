package eu.siacs.conversations.utils;

/**
 * TAKCONVO: constants from Android SDK 37 (CinnamonBun). The plugin compiles against SDK 36
 * because that is the newest one AGP 8.13 supports, and the ATAK takdev plugin needs AGP 8.
 */
public final class TakConvoCompat {

    /** {@code Build.VERSION_CODES.CINNAMON_BUN} */
    public static final int CINNAMON_BUN = 37;

    /** {@code Manifest.permission.ACCESS_LOCAL_NETWORK} */
    public static final String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";

    /**
     * True when Conversations runs embedded inside the ATAK process (the TAK Convo plugin) rather
     * than as its own app. There is then no real Android Service or Application: calls that need
     * one (foreground service, stopSelf, component toggling, telecom integration) are skipped.
     */
    public static volatile boolean EMBEDDED = false;

    /**
     * Additional trust anchors supplied by the plugin (e.g. a CA delivered in a mission package),
     * consulted by MemorizingTrustManager after the system CAs and before failing. Hostname
     * verification is unaffected.
     */
    public static volatile javax.net.ssl.X509TrustManager EXTRA_TRUST_MANAGER = null;

    private TakConvoCompat() {}
}
