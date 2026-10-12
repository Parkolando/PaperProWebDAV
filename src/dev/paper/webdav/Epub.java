package dev.paper.webdav;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the parts of an EPUB package document that Ridi's own parser ignores. */
final class Epub {
    /** Series position: EPUB 3 group-position or calibre:series_index; "" when absent. */
    static String seriesIndex(String opf) {
        Matcher match = Pattern.compile("<meta\\b[^>]*\\bgroup-position\\b[^>]*>\\s*([0-9]+(?:\\.[0-9]+)?)\\s*<").matcher(opf);
        if (match.find()) return match.group(1);
        match = Pattern.compile("<meta\\b[^>]*\\bcalibre:series_index\\b[^>]*>").matcher(opf);
        if (!match.find()) return "";
        match = Pattern.compile("\\bcontent\\s*=\\s*[\"']([0-9]+(?:\\.[0-9]+)?)[\"']").matcher(match.group());
        return match.find() ? match.group(1) : "";
    }

    /**
     * How strongly a manifest item looks like the cover: 0 declared with EPUB 3 properties="cover-image",
     * 1 an image file named "cover", 2 an image whose id or path mentions "cover" (a guess), -1 not a candidate.
     */
    static int coverRank(String properties, String type, String id, String href) {
        if (href == null) return -1;
        if (properties != null && (" " + properties + " ").contains(" cover-image ")) return 0;
        if (type == null || !type.startsWith("image/")) return -1;
        String path = href.toLowerCase(Locale.US);
        if (path.substring(path.lastIndexOf('/') + 1).startsWith("cover.")) return 1;
        return path.contains("cover") || (id != null && id.toLowerCase(Locale.US).contains("cover")) ? 2 : -1;
    }
}
