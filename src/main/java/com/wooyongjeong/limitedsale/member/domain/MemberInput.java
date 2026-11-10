package com.wooyongjeong.limitedsale.member.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class MemberInput {
    private static final Pattern PASSWORD = Pattern.compile("[!-~]{8,64}");
    private static final Pattern NICKNAME = Pattern.compile("[가-힣A-Za-z0-9 ]{1,12}");

    private MemberInput() {
    }

    public static String email(String input) {
        return input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean validPassword(String input) {
        return input != null && PASSWORD.matcher(input).matches();
    }

    public static String nickname(String input) {
        if (input == null) {
            return "";
        }
        return Normalizer.normalize(input, Normalizer.Form.NFC).replaceAll(" +", " ").replaceAll("^ +| +$", "");
    }

    public static boolean validNickname(String input) {
        return NICKNAME.matcher(input).matches();
    }

    public static String nicknameKey(String nickname) {
        return nickname.toLowerCase(Locale.ROOT);
    }
}
