package com.github.catvod.utils;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LocalScraper: Offline media metadata helper.
 * Finds companion poster images and parses NFO XML files
 * without any network access or TMDB calls.
 */
public class LocalScraper {

    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp"
    ));

    // Common default folder/poster image names (searched case-insensitively)
    private static final List<String> DEFAULT_POSTER_NAMES = Arrays.asList(
            "poster", "folder", "cover", "movie", "thumb", "fanart", "backdrop"
    );

    /**
     * Find a companion poster image for the given item name within the provided file name list.
     * Matches: {itemName}.jpg / {itemName}-poster.jpg / {itemName}-thumb.jpg etc.
     *
     * @param files    List of file names in the same directory (not full paths, just names).
     * @param itemName The video file or folder name to find a poster for (without extension).
     * @return The matched file name, or null if none found.
     */
    public static String getCompanionPoster(List<String> files, String itemName) {
        if (files == null || itemName == null || itemName.isEmpty()) return null;
        String base = stripExtension(itemName).toLowerCase(Locale.ROOT);
        String[] suffixes = {"", "-poster", "-thumb", "-cover", "-backdrop", "-fanart", ".poster", ".thumb"};
        for (String suffix : suffixes) {
            String target = base + suffix;
            for (String file : files) {
                String lower = file.toLowerCase(Locale.ROOT);
                String fileBase = stripExtension(lower);
                if (fileBase.equals(target) && isImage(lower)) {
                    return file;
                }
            }
        }
        return null;
    }

    /**
     * Find a default "folder-level" poster within the provided file name list.
     * Scans for common names like folder.jpg, poster.jpg, cover.jpg.
     *
     * @param files List of file names in the same directory.
     * @return The matched file name, or null if none found.
     */
    public static String getDefaultPoster(List<String> files) {
        if (files == null) return null;
        for (String defaultName : DEFAULT_POSTER_NAMES) {
            for (String file : files) {
                String lower = file.toLowerCase(Locale.ROOT);
                String base = stripExtension(lower);
                if (base.equals(defaultName) && isImage(lower)) {
                    return file;
                }
            }
        }
        return null;
    }

    /**
     * Check if a filename is an image file based on its extension.
     */
    public static boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        if (dot == -1) return false;
        return IMAGE_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Check if a filename is an NFO metadata file.
     */
    public static boolean isNfo(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".nfo");
    }

    /**
     * Strip the extension from a file name (e.g. "movie.mkv" -> "movie").
     */
    public static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Parse a NFO XML string and populate common metadata fields into the VOD JSONObject.
     * Supports: title, plot, outline, director, year, rating, genre, actor names.
     * Tolerates malformed XML — uses regex instead of DOM parsing for robustness.
     *
     * @param xml  The NFO file content as a string.
     * @param vod  The JSONObject to populate with metadata.
     */
    public static void parseNfo(String xml, JSONObject vod) {
        if (xml == null || xml.isEmpty() || vod == null) return;
        try {
            // Title
            String title = extractFirst(xml, "title");
            if (title != null && !title.isEmpty()) vod.put("vod_name", title);

            // Plot / description
            String plot = extractFirst(xml, "plot");
            if (plot == null || plot.isEmpty()) plot = extractFirst(xml, "outline");
            if (plot != null && !plot.isEmpty()) vod.put("vod_content", plot);

            // Director
            String director = extractFirst(xml, "director");
            if (director != null && !director.isEmpty()) vod.put("vod_director", director);

            // Year
            String year = extractFirst(xml, "year");
            if (year != null && !year.isEmpty()) vod.put("vod_year", year);

            // Rating (IMDB/TMDB rating → remarks field for quick display)
            String rating = extractFirst(xml, "rating");
            if (rating != null && !rating.isEmpty()) {
                try {
                    double r = Double.parseDouble(rating.trim());
                    String formatted = String.format(Locale.ROOT, "%.1f", r);
                    // Only set remarks if not already set to something better
                    if (vod.optString("vod_remarks", "").isEmpty()) {
                        vod.put("vod_remarks", "⭐ " + formatted);
                    }
                } catch (NumberFormatException ignored) {}
            }

            // Genre / type
            String genre = extractFirst(xml, "genre");
            if (genre == null || genre.isEmpty()) genre = extractFirst(xml, "category");
            if (genre != null && !genre.isEmpty()) vod.put("type_name", genre);

            // Actors — collect up to 5
            List<String> actorNames = extractAll(xml, "name", "actor");
            if (!actorNames.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int limit = Math.min(5, actorNames.size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) sb.append(" / ");
                    sb.append(actorNames.get(i));
                }
                vod.put("vod_actor", sb.toString());
            }

            // Poster image embedded in NFO (thumb/art)
            // Only use if vod_pic is still the default push_* placeholder
            String currentPic = vod.optString("vod_pic", "");
            if (currentPic.startsWith("push_")) {
                String thumb = extractFirst(xml, "thumb");
                if (thumb != null && (thumb.startsWith("http://") || thumb.startsWith("https://"))) {
                    vod.put("vod_pic", thumb);
                }
            }
        } catch (Exception e) {
            // Never crash the caller due to metadata parsing issues
        }
    }

    // -----------------------------------------------------------------------
    // Private XML extraction helpers (regex-based, tolerant of malformed XML)
    // -----------------------------------------------------------------------

    private static String extractFirst(String xml, String tag) {
        Pattern p = Pattern.compile(
                "<" + tag + "(?:\\s[^>]*)?>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</" + tag + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher m = p.matcher(xml);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    /**
     * Extract inner-tag values that appear within a named parent block.
     * Example: extract all <name> values inside <actor>...</actor> blocks.
     */
    private static List<String> extractAll(String xml, String innerTag, String outerTag) {
        java.util.ArrayList<String> results = new java.util.ArrayList<>();
        Pattern outerPattern = Pattern.compile(
                "<" + outerTag + "(?:\\s[^>]*)?>([\\s\\S]*?)</" + outerTag + ">",
                Pattern.CASE_INSENSITIVE
        );
        Matcher outerMatcher = outerPattern.matcher(xml);
        while (outerMatcher.find()) {
            String inner = outerMatcher.group(1);
            String val = extractFirst(inner, innerTag);
            if (val != null && !val.isEmpty()) results.add(val);
        }
        return results;
    }
}
