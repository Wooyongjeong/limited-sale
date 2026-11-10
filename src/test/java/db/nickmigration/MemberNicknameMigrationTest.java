package db.nickmigration;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberNicknameMigrationTest {
    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz가나다라마";

    @Test
    void signedLongEndpointsEncodeUniquelyWithinTwelveValidCharacters() throws Exception {
        Method encode = V3__member_nickname.class.getDeclaredMethod("encode", long.class);
        encode.setAccessible(true);
        long[] ids = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        Set<String> names = new HashSet<>();
        for (long id : ids) {
            String nickname = (String) encode.invoke(null, id);
            assertThat(nickname).matches("[가-힣a-z0-9]{1,12}");
            assertThat(names.add(nickname)).isTrue();
            BigInteger decoded = BigInteger.ZERO;
            for (char digit : nickname.toCharArray()) {
                decoded = decoded.multiply(BigInteger.valueOf(DIGITS.length()))
                    .add(BigInteger.valueOf(DIGITS.indexOf(digit)));
            }
            assertThat(decoded.add(BigInteger.valueOf(Long.MIN_VALUE))).isEqualTo(BigInteger.valueOf(id));
        }
        assertThat(BigInteger.valueOf(41).pow(12)).isGreaterThan(BigInteger.ONE.shiftLeft(64));
    }
}
