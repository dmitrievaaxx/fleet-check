package ru.x5.practice.fleet.importing;

/**
 * Пробелы по контракту данных (1.2): обрезаются все пробельные символы Unicode, включая
 * табуляцию и неразрывный пробел. {@link String#strip()} неразрывные пробелы не считает
 * пробельными, поэтому своя проверка.
 */
public final class Text {

    private Text() {}

    public static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }

    public static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** Длина в кодовых точках Unicode, как требует контракт, а не в единицах UTF-16. */
    public static int length(String s) {
        return s.codePointCount(0, s.length());
    }
}
