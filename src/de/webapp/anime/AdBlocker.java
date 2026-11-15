package de.webapp.anime;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Werbe-/Tracker-Domains (gleiche Liste wie die Desktop-Erweiterung). */
final class AdBlocker {
    private static final Set<String> DOMAINS = new HashSet<>(Arrays.asList(
        "2mdn.net", "a-ads.com", "acint.net", "ad-maven.com", "ad.mail.ru", "adcash.com", "adfox.ru",
        "adform.net", "adition.com", "admaven.com", "adnxs.com", "adservice.google.com", "adskeeper.co.uk",
        "adskeeper.com", "adspyglass.com", "adsrvr.org", "adsterra.com", "adsterratech.com",
        "amazon-adsystem.com", "an.yandex.ru", "bidgear.com", "bitmedia.io", "casalemedia.com",
        "clickadu.com", "clickaine.com", "coinzilla.com", "cpmstar.com", "criteo.com", "criteo.net",
        "displayvertising.com", "dolohen.com", "doubleclick.net", "effectivegatecpm.com",
        "effectiveratecpm.com", "ero-advertising.com", "exoclick.com", "exosrv.com", "galaksion.com",
        "googleadservices.com", "googlesyndication.com", "googletagservices.com",
        "highperformancecpm.com", "highperformanceformat.com", "hilltopads.com", "hilltopads.net",
        "histats.com", "hotjar.com", "juicyads.com", "ligatus.com", "lkqd.net", "mc.yandex.ru",
        "mgid.com", "moatads.com", "monetag.com", "onclicka.com", "onclickads.net", "onclkds.com",
        "openx.net", "plista.com", "plugrush.com", "popads.net", "popcash.net", "popmyads.com",
        "poptm.com", "popunder.net", "profitablecpmrate.com", "profitablegatecpm.com",
        "propellerads.com", "propellerclick.com", "pubmatic.com", "pushground.com", "pushnami.com",
        "quantserve.com", "realsrv.com", "revcontent.com", "richads.com", "richpartners.co",
        "rubiconproject.com", "scorecardresearch.com", "smartadserver.com", "spotxchange.com",
        "statcounter.com", "stroeerdigitalgroup.de", "taboola.com", "trafficjunky.com",
        "trafficjunky.net", "tsyndicate.com", "wpadmngr.com", "yandexadexchange.net", "yieldlab.net",
        "yieldlove.com", "yllix.com", "zedo.com", "outbrain.com",
        // Monetag/Propeller "In-Page-Push" (falsche Benachrichtigungen), gesehen auf aniworld.to
        "rtmark.net", "llvpn.com", "ueuee.com", "axgbr.com", "luugy.com"
    ));

    /** Zusaetzliche Domains aus der Online-Sperrliste (GitHub), wird zur Laufzeit gesetzt. */
    static volatile Set<String> remote = new HashSet<>();

    /** Monetag-Muster: <zufall>.com/tag.min.js bzw. /400/<zone>, /5/<zone>/ … */
    private static final java.util.regex.Pattern AD_PATH = java.util.regex.Pattern.compile(
        "^/(tag\\.min\\.js|sw\\.js|(4|5|400|401|500|501)/\\d{6,9}(/.*)?)$");

    /** Domain auf der Liste ODER typisches Werbe-Muster bei einer fremden (Dritt-)Domain. */
    static boolean isAdRequest(String host, String path, String pageHost) {
        if (isAd(host)) return true;
        if (host == null || path == null) return false;
        boolean thirdParty = pageHost == null || !baseDomain(host).equals(baseDomain(pageHost));
        return thirdParty && AD_PATH.matcher(path).matches();
    }

    static final String COSMETIC_CSS =
        "ins.adsbygoogle,.adsbygoogle,[id^=\"google_ads_\"],[id^=\"div-gpt-ad\"]," +
        "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication\"],iframe[id^=\"google_ads_iframe\"]," +
        "[id^=\"taboola-\"],.OUTBRAIN,[class*=\"ad-banner\"],[class*=\"adsbox\"],[id*=\"ad-banner\"]," +
        "a[href*=\"/aff_c?\"],a[href*=\"exoclick\"],a[href*=\"popads\"]{display:none!important}";

    private AdBlocker() {}

    static boolean isAd(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        while (true) {
            if (DOMAINS.contains(h) || remote.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    /** "a.b.example.com" -> "example.com" (einfache Naeherung). */
    static String baseDomain(String host) {
        if (host == null) return "";
        String[] p = host.toLowerCase().split("\\.");
        if (p.length <= 2) return host.toLowerCase();
        return p[p.length - 2] + "." + p[p.length - 1];
    }
}
